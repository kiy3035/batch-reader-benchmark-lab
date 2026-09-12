# Spring Batch OFFSET 페이징, 데이터가 커지면 얼마나 느려질까? 100만 건까지 직접 읽어봤다

Spring Batch로 많은 데이터를 순서대로 읽을 때 `JpaPagingItemReader`부터 떠올리는 경우가 많다.

JPQL과 `pageSize`만 정하면 페이지 이동과 재시작 상태를 프레임워크가 관리해 준다. 구현도 익숙하다.

그런데 이 Reader는 뒤쪽 페이지로 갈수록 OFFSET이 커진다. 앞에서 이미 읽은 행을 계속 건너뛰는 비용이 전체 Step에도 눈에 띄게 쌓일까?

궁금해서 같은 정산 데이터를 두 방식으로 끝까지 읽어봤다.

- Spring Batch의 실제 `JpaPagingItemReader`
- `WHERE id > :lastId`로 다음 페이지를 찾는 Keyset Reader

실제 회사 정산 데이터나 운영 환경을 재현한 건 아니다. Windows 노트북의 Docker 환경과 결정적으로 생성한 합성 데이터로 진행한 개인 실험이다.

결과부터 짧게 적으면 이렇다.

> 100만 건, 보조 인덱스 OFF 조건에서 OFFSET은 평균 177.140초, Keyset은 19.504초가 걸렸다. 약 9.082배 차이였다.

10만 건에서는 1.236배였던 차이가 데이터가 늘면서 커졌다. 왜 이런 모양이 나왔는지 실행계획까지 따라가 봤다.

## 두 Reader를 같은 조건에 놓았다

가상의 정산 Batch가 `READY` 데이터를 ID 오름차순으로 읽는 상황을 만들었다. 한 번에 읽고 commit하는 크기는 모두 1,000건이다.

OFFSET 쪽은 비교를 위해 새 Reader를 따로 만들지 않았다. Spring Batch의 `JpaPagingItemReader<SettlementItem>`를 그대로 썼다.

논리적인 SQL은 다음과 같다.

```sql
SELECT ...
FROM settlement_item
WHERE status = 'READY'
ORDER BY id
LIMIT 1000 OFFSET :offset;
```

100만 건의 마지막 페이지라면 OFFSET은 999,000이다. 마지막 1,000건을 돌려주기 전에 앞의 행부터 지나가야 한다.

Keyset Reader는 마지막으로 읽은 ID를 다음 조회 조건에 넣었다.

```sql
SELECT ...
FROM settlement_item
WHERE status = 'READY'
  AND id > :lastId
ORDER BY id
LIMIT 1000;
```

페이지 번호는 없다. 직전 ID 다음부터 1,000건을 찾는 방식이다.

둘 다 같은 Entity, Processor, Writer를 사용했다. Writer는 다른 DB에 결과를 저장하지 않고 처리 건수와 checksum만 누적했다. 비교하려는 대상이 Reader였기 때문이다.

## 실험 조건

READY 데이터는 100,000건, 500,000건, 1,000,000건으로 만들었다. 각 규모에는 10%의 `COMPLETED` 데이터도 함께 넣었다.

비교 조건은 다음처럼 고정했다.

- `status = READY`, `id ASC`
- `pageSize=1000`, `chunkSize=1000`
- 단일 스레드 Step
- Reader 실행마다 별도 JVM
- G1GC, `-Xms512m -Xmx512m`
- OFFSET / Keyset, 보조 인덱스 OFF / ON
- 조건별 warm-up 1회 뒤 공식 측정 3회

전체 공식 측정은 `3개 데이터 규모 × 2개 Reader × 2개 인덱스 조건 × 3회`로 36회다.

항상 먼저 실행한 Reader가 유리하지 않도록 반복마다 순서를 바꿨다. OS page cache는 강제로 비우지 않았으므로 결과 범위는 로컬 warm-cache 비교까지다.

## 데이터가 늘수록 차이가 벌어졌다

아래 값은 조건별 Step wall-clock 3회 평균이다.

| READY 행 | OFFSET OFF | Keyset OFF | 비율 | OFFSET ON | Keyset ON | 비율 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100k | 5.924초 | 4.794초 | 1.236배 | 7.346초 | 5.553초 | 1.323배 |
| 500k | 48.620초 | 12.105초 | 4.017배 | 41.703초 | 13.437초 | 3.104배 |
| 1m | 177.140초 | 19.504초 | 9.082배 | 132.354초 | 18.310초 | 7.228배 |

10만 건만 봤다면 `JpaPagingItemReader`의 단순함을 선택했을지도 모른다. 차이는 1~2초 정도였다.

하지만 50만 건에서는 3.104~4.017배, 100만 건에서는 7.228~9.082배까지 벌어졌다.

100k에서 1m으로 데이터가 10배 늘어나는 동안 OFFSET 실행시간은 18.018~29.903배가 됐다. Keyset은 같은 구간에서 3.298~4.068배였다.

특정 환경에서 나온 배율 자체보다 증가 모양이 더 눈에 들어왔다.

## 마지막 페이지의 실행계획을 열어봤다

실행시간만 보고 원인을 단정할 수는 없다. 그래서 100만 건의 첫 페이지, 중간 페이지, 마지막 페이지를 실제 SQL과 바인딩 값으로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` 했다.

보조 인덱스 OFF 조건의 결과다.

| Reader | 위치 | scanActualRows | 반환 행 | 실행시간 |
| --- | --- | ---: | ---: | ---: |
| OFFSET | FIRST | 1,000 | 1,000 | 0.370ms |
| OFFSET | MIDDLE | 501,000 | 1,000 | 131.198ms |
| OFFSET | LAST | 1,000,000 | 1,000 | 220.363ms |
| Keyset | FIRST | 1,000 | 1,000 | 0.290ms |
| Keyset | MIDDLE | 1,000 | 1,000 | 0.387ms |
| Keyset | LAST | 1,000 | 1,000 | 0.649ms |

OFFSET의 마지막 페이지는 1,000건을 반환하려고 1,000,000행을 통과했다. Keyset은 같은 위치에서도 `id > lastId`가 PK의 `Index Cond`로 들어가 1,000행만 읽었다.

buffer도 방향은 같았다. 마지막 페이지의 OFFSET OFF는 shared read 23,119 blocks, Keyset OFF는 shared hit 26 blocks와 read 2 blocks였다.

hit과 read의 비율은 실행 시점의 cache 상태에 따라 달라질 수 있다. 그래도 두 Reader가 마지막 1,000건에 도달하기 위해 통과한 행과 block 규모가 달랐다는 점은 원본 실행계획에서 확인됐다.

## 보조 인덱스 결과는 예상과 달랐다

켜고 끈 인덱스는 `(status, id)`였다.

100만 건 OFFSET 평균은 OFF 177.140초, ON 132.354초였다. 숫자만 보면 인덱스가 약 25.3% 개선한 것처럼 보인다.

그런데 공식 실행계획 36개에서 PostgreSQL이 고른 건 모두 기본 키 인덱스 `settlement_item_pkey`였다. 보조 인덱스는 한 번도 선택되지 않았다.

마지막 OFFSET 쿼리 한 번의 실행시간도 OFF 220.363ms, ON 232.742ms로 Step 평균과 방향이 반대였다.

이번 seed는 READY 데이터를 ID 앞부분에 연속으로 배치했다. 측정 페이지의 `Rows Removed by Filter`도 모두 0이었다.

따라서 `보조 인덱스가 OFFSET을 25.3% 개선했다`고 말할 근거는 부족하다. 확인된 사실은 인덱스 모드별 Step 시간에 차이가 있었지만, PostgreSQL이 실제로 선택한 scan은 모두 PK 인덱스였다는 것까지다.

## GC 때문에 느린지도 확인했다

두 Reader 모두 페이지와 chunk 1,000개만 유지했다. Step 중 50ms 간격으로 측정한 G1 Old Gen 최대값도 50만·100만 건에서 약 2.3MiB로 비슷했다.

Full GC와 OOM은 공식 36회 모두 0건이었다.

100만 건 OFF 조건의 JVM 전체 pause 합계 평균은 OFFSET 288.607ms, Keyset 198.961ms였다. 반면 Step 시간 차이는 약 158초다.

이 관측은 큰 시간 차이를 GC pause로 설명하는 쪽을 지지하지 않았다. 다만 GC 로그는 JVM 부팅부터 종료까지 포함하므로 Step 전용 pause라고 표현할 수는 없다.

## 빠르기 전에 같은 데이터를 읽었는지 확인했다

Keyset이 빨라도 누락이나 중복이 있으면 쓸 수 없다.

마지막으로 정상 반환한 ID는 chunk commit과 함께 `ExecutionContext`에 저장했다. chunk가 실패하면 checkpoint도 같이 rollback된다.

0건, 1건, 999건, 1,000건, 1,001건, ID gap, 다른 상태 혼합, 두 번째 chunk 실패 뒤 재시작을 테스트했다. 1,001건 재시작 시나리오에서는 OFFSET과 Keyset 모두 각 ID를 정확히 한 번 commit했다.

공식 측정 36회도 모두 `COMPLETED`였다. read/write 수는 목표 READY 건수와 같았고, 같은 데이터 규모의 checksum도 Reader와 인덱스 조건에 관계없이 일치했다.

- 100k: `202,165,400,000`
- 500k: `4,121,634,000,000`
- 1m: `16,000,015,000,000`

프로젝트 완료 시점의 JUnit 기록은 20개 통과, failures·errors·skipped 0이다. 이번 글을 다시 검토하면서 결과 검증 스크립트도 실행했고 공식 run 36개, summary 12개, EXPLAIN 36개, GC 로그 36개와 checksum 재계산이 모두 통과했다.

## 그렇다면 언제 Keyset을 쓸까

고유하고 변하지 않는 ID를 따라 대량 데이터를 끝까지 읽는 Batch라면 Keyset이 잘 맞았다. 뒤쪽 페이지에서도 직전 ID 다음의 1,000건만 찾을 수 있기 때문이다.

물론 공짜는 아니다.

- 정렬 키가 고유하고 안정적이어야 한다.
- 재시작용 `lastId` checkpoint를 직접 관리해야 한다.
- 임의의 페이지 번호로 바로 이동하기 어렵다.
- 여러 정렬 조건을 지원하는 화면 조회에는 그대로 적용하기 어렵다.

작은 데이터에서는 `JpaPagingItemReader`의 단순함이 더 중요할 수 있다. 반대로 정산이나 마이그레이션처럼 많은 행을 같은 순서로 훑는 작업이라면 마지막 페이지까지 DB가 몇 행을 통과하는지 먼저 보는 편이 낫다.

## 직접 돌려보려면

Java 21과 Docker Compose가 필요하다.

```powershell
git clone https://github.com/kiy3035/batch-reader-benchmark-lab.git
cd batch-reader-benchmark-lab

.\gradlew.bat --no-daemon test
.\scripts\run-full-matrix.ps1
.\scripts\validate-results.ps1
.\scripts\build-report-data.ps1
```

100만 건 OFFSET까지 실행하므로 환경에 따라 시간이 꽤 걸린다. 중간에 멈췄다면 `run-full-matrix.ps1 -Resume`으로 완료한 run을 유지한 채 이어갈 수 있다.

전체 소스, 원본 JSON·CSV, EXPLAIN과 GC 로그는 저장소에 남겨뒀다.

- [batch-reader-benchmark-lab GitHub 저장소](https://github.com/kiy3035/batch-reader-benchmark-lab)

## 마치며

처음에는 두 방식 모두 결국 100만 건을 읽으니 차이가 아주 크지는 않을 거라 생각했다.

실제로 10만 건에서는 1.236~1.323배 차이였다. 하지만 100만 건에서는 7.228~9.082배까지 벌어졌다.

EXPLAIN을 열어보니 이유가 좀 더 선명했다. OFFSET의 마지막 페이지는 마지막 1,000건을 반환하려고 100만 행을 통과했고, Keyset은 같은 위치에서도 1,000행만 읽었다.

반대로 보조 인덱스와 GC는 처음 예상한 설명을 지지하지 않았다. 인덱스를 켠 일부 Step은 빨랐지만 PostgreSQL은 그 인덱스를 선택하지 않았고, GC pause 차이도 전체 실행시간 차이에 비하면 작았다.

그러니 이 결과를 `Keyset은 항상 9배 빠르다`고 일반화할 수는 없다. 한 Windows 노트북, Docker, READY가 ID 앞쪽에 모인 합성 데이터, pageSize와 chunkSize 1,000, 가벼운 Writer라는 조건이 붙는다.

이번 실험에서 확인한 건 조금 더 좁다.

대량 Batch를 순서대로 끝까지 읽을 때, OFFSET의 후반 페이지 비용은 데이터가 커질수록 전체 Step에 누적됐다. Reader를 익숙함만으로 고르기 전에 마지막 페이지의 실행계획을 한 번 열어볼 이유는 충분했다.
