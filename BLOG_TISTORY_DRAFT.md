# Spring Batch OFFSET 페이징, 데이터가 커지면 얼마나 느려질까? 100만 건까지 직접 읽어봤다

Spring Batch로 많은 데이터를 순서대로 읽어야 할 때 가장 먼저 떠오르는 건 `JpaPagingItemReader`다.

JPQL과 `pageSize`를 설정하면 페이지 이동과 재시작 상태를 Spring Batch가 관리해 준다. 구현도 단순한 편이다.

문제는 이 Reader가 사용하는 OFFSET 방식이었다.

앞쪽 페이지에서는 별문제가 없어 보여도 뒤로 갈수록 이미 지나온 행을 계속 건너뛰어야 한다. 데이터가 10만 건일 때는 작았던 차이가 50만 건, 100만 건에서도 그대로 작을까?

직접 확인해 보기 위해 같은 정산 데이터를 두 가지 Reader로 끝까지 읽어봤다.

- Spring Batch의 실제 `JpaPagingItemReader`
- `WHERE id > :lastId` 조건을 사용하는 Keyset Reader

실제 회사 정산 데이터나 운영 환경을 재현한 것은 아니다.

Windows 노트북의 Docker 환경과 결정적으로 생성한 합성 데이터로 만든 개인 실험이다.

먼저 결과부터. 100만 건에서 OFFSET은 평균 177.140초, Keyset은 평균 19.504초가 걸렸다. 같은 인덱스 OFF 조건에서 약 9.082배 차이다.

| Reader | 보조 인덱스 OFF | 보조 인덱스 ON |
| --- | ---: | ---: |
| JpaPagingItemReader OFFSET | 177.140초 | 132.354초 |
| Keyset | 19.504초 | 18.310초 |
| OFFSET / Keyset | 9.082배 | 7.228배 |

물론 이 결과만으로 Keyset이 언제나 9배 빠르다고 말할 수는 없다.

이번 실험에서 확인하고 싶었던 건 특정 배율 자체가 아니었다. 데이터가 늘어날 때 두 Reader의 실행시간이 어떤 모양으로 증가하는지, 그 차이가 PostgreSQL 실행계획에서 어떻게 보이는지가 더 궁금했다.

## 문제 상황을 이렇게 잡았다

가상의 정산 Batch가 있다고 가정했다.

`READY` 상태의 정산 데이터를 ID 오름차순으로 읽고, 1,000건마다 처리 결과를 commit하는 작업이다.

OFFSET 방식의 논리적인 SQL은 다음과 같다.

```sql
SELECT ...
FROM settlement_item
WHERE status = 'READY'
ORDER BY id
LIMIT 1000 OFFSET :offset;
```

첫 페이지의 OFFSET은 0.

하지만 100만 건의 마지막 페이지에 도달하면 앞의 999,000건을 건너뛴 뒤 마지막 1,000건을 반환해야 한다.

반면 Keyset 방식은 마지막으로 읽은 ID를 다음 조회 조건으로 사용한다.

```sql
SELECT ...
FROM settlement_item
WHERE status = 'READY'
  AND id > :lastId
ORDER BY id
LIMIT 1000;
```

페이지 번호를 계산하지 않고 마지막 ID 다음부터 다시 읽는 구조다.

처음에는 두 방식 모두 결국 100만 건을 읽으니 전체 실행시간 차이가 아주 크지는 않을 수도 있다고 생각했다.

막상 실행해 보니 뒤쪽 페이지를 찾는 비용이 전체 Step 시간에 계속 누적됐다.

## 직접 만든 Reader가 아니라 JpaPagingItemReader와 비교했다

OFFSET 쪽을 일부러 불리하게 구현하면 비교 결과에 의미가 없다.

그래서 OFFSET Reader에는 Spring Batch의 실제 `JpaPagingItemReader<SettlementItem>`를 그대로 사용했다.

```java
SELECT s
FROM SettlementItem s
WHERE s.status = :status
ORDER BY s.id ASC
```

JPQL에 `LIMIT`를 직접 쓰지 않고 `pageSize=1000`을 적용했다. Hibernate가 `setFirstResult`와 `setMaxResults`를 PostgreSQL의 LIMIT/OFFSET SQL로 변환하는 방식이다.

Keyset Reader는 `ItemStreamReader`로 구현했다.

```java
SELECT s
FROM SettlementItem s
WHERE s.status = :status
  AND s.id > :lastId
ORDER BY s.id ASC
```

한 페이지를 모두 읽으면 그 페이지의 마지막 ID가 다음 조회의 `lastId`가 된다. JPA의 `setMaxResults(1000)`을 적용해 한 번에 전체 데이터를 메모리에 올리지 않았다.

전체 흐름은 다음과 같다.

```text
PostgreSQL 합성 데이터
        ↓
OFFSET 또는 Keyset Reader
        ↓
같은 checksum Processor
        ↓
같은 집계 Writer
        ↓
count·checksum·실행시간·Old Gen 기록
```

이 프로젝트의 주인공은 Processor나 Writer가 아니라 Reader다.

그래서 Writer는 다른 DB에 결과를 저장하지 않고 처리 건수와 checksum만 누적했다. 전체 ID 목록이나 Entity도 쌓아두지 않았다.

## 비교 조건부터 같게 맞췄다

두 Reader에는 다음 조건을 똑같이 적용했다.

- 조회 조건: `status = READY`
- 정렬: `id ASC`
- `pageSize=1000`
- `chunkSize=1000`
- 같은 JPA Entity와 조회 컬럼
- 같은 Processor와 Writer
- 단일 스레드 Step
- Reader 실행마다 별도 JVM
- G1GC, `-Xms512m -Xmx512m`
- Step 실행 중 Old Gen 50ms 간격 측정

합성 데이터는 PostgreSQL `generate_series`로 만들었다.

READY 데이터는 100,000건, 500,000건, 1,000,000건으로 준비했고 각 규모마다 10%의 COMPLETED 데이터도 추가했다. 고객 ID, 금액, 시각, 참조번호와 JSON payload는 ID만으로 계산했다. 같은 규모를 다시 만들면 같은 값과 checksum이 나오는 구조다.

실험 조합은 다음과 같다.

```text
3개 데이터 규모
× OFFSET / Keyset
× 보조 인덱스 OFF / ON
× 3회 반복
= 공식 측정 36회
```

각 조건은 공식 측정 전에 한 번씩 warm-up했다. warm-up 결과는 평균에서 제외.

항상 먼저 실행한 Reader가 cache 이점을 가져가지 않도록 반복 순서도 바꿨다.

- 1회차: OFFSET → Keyset
- 2회차: Keyset → OFFSET
- 3회차: OFFSET → Keyset

인덱스 순서 역시 OFF→ON, ON→OFF, OFF→ON으로 교차했다.

OS page cache는 강제로 비우지 않았다. 관리자 권한 없이 완전한 cold cache를 만들었다고 주장할 수 없기 때문이다.

따라서 아래 결과의 범위는 실행 순서를 기록하고 warm-up한 로컬 warm-cache 비교까지다.

## 결과: 10만 건에서는 작았던 차이가 100만 건에서 벌어졌다

각 조건을 세 번 실행한 Step wall-clock 평균은 다음과 같다.

| READY 행 | OFFSET OFF | Keyset OFF | OFFSET/Keyset | OFFSET ON | Keyset ON | OFFSET/Keyset |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100k | 5.924초 | 4.794초 | 1.236배 | 7.346초 | 5.553초 | 1.323배 |
| 500k | 48.620초 | 12.105초 | 4.017배 | 41.703초 | 13.437초 | 3.104배 |
| 1m | 177.140초 | 19.504초 | 9.082배 | 132.354초 | 18.310초 | 7.228배 |

100k에서는 약 1~2초 차이.

이 정도 규모만 봤다면 `JpaPagingItemReader`가 충분히 단순하고 빠르다고 정리했을 수도 있다.

하지만 500k에서는 3.104~4.017배, 1m에서는 7.228~9.082배까지 벌어졌다.

데이터 증가 배율도 따로 비교해 봤다.

| Reader | Index | 500k / 100k | 1m / 100k | 1m / 500k |
| --- | --- | ---: | ---: | ---: |
| OFFSET | OFF | 8.208배 | 29.903배 | 3.643배 |
| OFFSET | ON | 5.677배 | 18.018배 | 3.174배 |
| Keyset | OFF | 2.525배 | 4.068배 | 1.611배 |
| Keyset | ON | 2.420배 | 3.298배 | 1.363배 |

READY 행 수는 100k에서 1m으로 10배가 됐다.

같은 구간에서 OFFSET 실행시간은 18.018~29.903배, Keyset은 3.298~4.068배가 됐다.

두 Reader 모두 10배 많은 Entity를 만들고 Processor를 실행한다.

여기에 OFFSET은 페이지가 뒤로 갈수록 앞 행을 통과하는 비용까지 더해진다.

## EXPLAIN에서 마지막 페이지를 확인해 봤다

실행시간만으로는 다른 시스템 부하나 JVM 상태 때문에 차이가 생겼을 가능성도 남는다.

그래서 100만 건 데이터의 첫 페이지, 중간 페이지, 마지막 페이지를 실제 SQL과 바인딩 값으로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` 했다.

아래 표는 보조 인덱스 OFF 조건이다.

| Reader | 위치 | scanActualRows | 반환 행 | 실행시간 |
| --- | --- | ---: | ---: | ---: |
| OFFSET | FIRST | 1,000 | 1,000 | 0.370ms |
| OFFSET | MIDDLE | 501,000 | 1,000 | 131.198ms |
| OFFSET | LAST | 1,000,000 | 1,000 | 220.363ms |
| Keyset | FIRST | 1,000 | 1,000 | 0.290ms |
| Keyset | MIDDLE | 1,000 | 1,000 | 0.387ms |
| Keyset | LAST | 1,000 | 1,000 | 0.649ms |

OFFSET의 첫 페이지는 1,000행을 통과시켜 1,000행을 반환했다.

중간 페이지에서는 501,000행을 통과한 뒤 1,000행을 반환했다. 마지막 페이지에서는 1,000,000행을 통과하고서야 마지막 1,000행이 나왔다.

Keyset은 달랐다.

첫 페이지, 중간 페이지, 마지막 페이지 모두 `id > lastId`가 PK의 `Index Cond`로 들어갔다. `scanActualRows`는 세 위치 모두 1,000행.

접근한 buffer 규모에서도 같은 차이가 보였다.

100만 건 마지막 페이지에서 OFFSET OFF는 shared read 23,119 blocks였다. 같은 위치의 Keyset OFF는 shared hit 26 blocks와 read 2 blocks.

cache에 있던 block과 디스크에서 읽은 block의 비율은 실행 시점에 따라 달라질 수 있다.

그래도 마지막 1,000건을 찾기 위해 OFFSET이 훨씬 많은 행과 block을 통과했다는 사실은 원본 실행계획에서 확인할 수 있었다.

## 보조 인덱스를 켰는데 PostgreSQL은 그 인덱스를 쓰지 않았다

이번에 켜고 끈 보조 인덱스는 다음과 같다.

```sql
CREATE INDEX idx_settlement_item_status_id
    ON settlement_item (status, id);
```

100만 건 OFFSET 평균만 보면 OFF 177.140초, ON 132.354초다. ON이 약 25.3% 짧았다.

여기까지만 보면 `(status, id)` 인덱스가 OFFSET을 개선했다고 결론 내리기 쉽다.

그런데 EXPLAIN 결과는 그 설명을 지지하지 않았다.

공식 실행계획 36개 모두 보조 인덱스가 아니라 기본 키 인덱스인 `settlement_item_pkey`를 선택했다.

100만 건 마지막 OFFSET 쿼리 한 번의 실행시간도 OFF 220.363ms, ON 232.742ms. 전체 Step 평균과 반대 방향이다.

왜 이런 결과가 나왔을까. 데이터를 다시 봤다.

이번 seed는 READY 데이터를 ID 앞부분에 연속으로 배치했다. 측정한 READY 페이지에서 `Rows Removed by Filter`도 모두 0이었다.

즉, 상태가 복잡하게 섞인 데이터나 READY 비율이 낮은 데이터에서 기대할 수 있는 보조 인덱스의 선택도 효과를 이번 분포에서는 제대로 만들지 못했다.

그래서 이 결과를 `보조 인덱스가 OFFSET을 25.3% 개선했다`고 정리하지 않았다.

확인한 사실은 여기까지다. 인덱스 모드별 Step 시간에는 차이가 있었지만 PostgreSQL이 실제로 선택한 scan은 모두 PK 인덱스였다.

로컬 cache, Docker와 호스트 부하, 세 번뿐인 반복에서 생긴 편차도 남아 있다.

## Old Gen과 GC가 원인인지도 확인했다

처음에는 OFFSET Reader가 뒤로 갈수록 더 많은 Entity를 메모리에 남겨서 느려지는 건 아닐까 생각했다.

Step 실행 중 `MemoryPoolMXBean`의 `G1 Old Gen`을 50ms마다 읽어 최대값을 기록했다.

| READY 행 | Keyset OFF / ON | OFFSET OFF / ON |
| ---: | ---: | ---: |
| 100k | 0 / 0 MiB | 0 / 0 MiB |
| 500k | 2.305 / 2.292 MiB | 2.304 / 2.333 MiB |
| 1m | 2.317 / 2.313 MiB | 2.304 / 2.321 MiB |

100k의 0은 측정 실패가 아니다. 50ms 간격으로 Step을 측정하는 동안 Old Gen 사용이 관찰되지 않았다는 뜻이다.

500k와 1m에서는 두 Reader 모두 약 2.3MiB였다.

두 구현 모두 한 번에 페이지와 chunk 1,000개만 유지했다. Writer도 전체 결과를 모으지 않았기 때문에 Reader에 따른 Old Gen 차이는 크지 않았다.

Unified GC 로그도 함께 확인했다.

- 모든 실행에서 G1GC와 512MiB heap 사용
- Full GC 0회
- OOM 0회
- 완료된 pause 수는 100k 12회, 500k 16회, 1m 21회

100만 건 OFFSET OFF의 JVM 전체 pause 합계 평균은 288.607ms, Keyset OFF는 198.961ms였다.

GC pause에는 차이가 있었지만 Step 실행시간 차이는 약 158초였다.

이 정도 차이를 GC pause만으로 설명하기는 어렵다. 핵심은 JVM이 Entity를 오래 들고 있는 방식보다 DB가 다음 페이지에 도달하는 방식에 가까웠다.

다만 GC 로그는 Step 구간만이 아니라 JVM 부팅부터 종료까지를 포함한다. 정확한 Step 전용 GC pause라고 표현할 수는 없다.

## 빠르기 전에 같은 데이터를 읽었는지 확인했다

Keyset Reader가 빨라도 데이터를 건너뛰거나 중복 처리하면 사용할 수 없다.

그래서 실행시간보다 먼저 정합성과 재시작부터 확인했다.

Keyset Reader는 마지막으로 정상 반환한 ID를 chunk commit과 함께 `ExecutionContext`에 저장한다. chunk가 실패하면 해당 checkpoint도 rollback되는 구조다.

테스트에는 다음 경우를 포함했다.

- 대상 데이터 0건
- 1건, 999건, 1,000건, 1,001건
- 마지막 페이지가 1,000건보다 작은 경우
- ID 사이에 gap이 있는 경우
- READY 이외 상태가 섞인 경우
- 두 번째 chunk에서 실패한 뒤 같은 JobInstance를 재시작하는 경우

1,001건 테스트에서는 두 번째 chunk를 일부러 실패시킨 뒤 재시작했다.

결과는 OFFSET과 Keyset 모두 각 ID를 정확히 한 번 commit.

공식 측정 36회도 모두 `COMPLETED`였고 read/write 수는 목표 READY 건수와 같았다. 같은 데이터 규모에서는 Reader와 인덱스 조건이 달라도 checksum이 일치했다.

| READY 행 | checksum |
| ---: | ---: |
| 100k | 202,165,400,000 |
| 500k | 4,121,634,000,000 |
| 1m | 16,000,015,000,000 |

프로젝트 완료 당시 병합된 `main`에서는 JUnit 테스트 20개가 통과했다. 기록된 failures, errors, skipped는 모두 0이다.

이번 원고를 검토하면서 측정 결과 검증 스크립트도 다시 실행했다. 확인한 내용은 다음과 같다.

- 공식 run 36개
- 조건별 summary 12개
- GC 로그 36개
- EXPLAIN JSON 36개
- 실행 순서 36개
- 원본 JSON에서 summary 재계산

## 그렇다면 언제 Keyset을 선택할까

이번 실험처럼 고유하고 변하지 않는 ID를 따라 대량 데이터를 처음부터 끝까지 읽는 Batch라면 Keyset이 잘 맞았다.

페이지가 뒤로 가더라도 `id > lastId` 다음의 1,000건만 찾는다. OFFSET의 누적 skip 비용을 피할 수 있었던 이유다.

그렇다고 Keyset이 공짜인 것은 아니다.

- 정렬 키가 고유하고 안정적이어야 함
- 방향이 정해진 순차 탐색에 적합함
- 재시작을 위한 `lastId` checkpoint를 직접 관리해야 함
- 임의의 페이지 번호로 바로 이동하기 어려움
- 여러 사용자 정렬 조건이 필요한 화면 조회에는 그대로 적용하기 어려움

작은 데이터에서는 `JpaPagingItemReader`의 단순함이 성능 차이보다 중요할 수 있다.

반대로 정산·마이그레이션처럼 많은 행을 같은 순서로 끝까지 읽는 작업이라면 이야기가 달라진다. 데이터가 커질 때 OFFSET 비용이 어떻게 증가하는지 먼저 확인할 필요가 있다.

## 직접 실행해 보고 싶다면

실행에는 Java 21과 Docker Compose가 필요하다.

```powershell
git clone https://github.com/kiy3035/batch-reader-benchmark-lab.git
cd batch-reader-benchmark-lab

.\gradlew.bat --no-daemon test
.\scripts\run-full-matrix.ps1
.\scripts\validate-results.ps1
.\scripts\build-report-data.ps1
```

전체 matrix는 100만 건 OFFSET 실행까지 포함한다. 환경에 따라 꽤 오래 걸릴 수 있다.

중간에 멈췄다면 이미 완료한 유효 run을 유지한 채 다음 명령으로 재개할 수 있다.

```powershell
.\scripts\run-full-matrix.ps1 -Resume
```

원본과 요약 파일은 다음 위치에 남는다.

- `results/runs/*.json`: 실행별 원본
- `results/raw-runs.csv`: 공식 36개 run 표
- `results/summary.csv`: 12개 조건 요약
- `results/explain/*.json`: 실제 SQL·바인딩·실행계획
- `results/gc/*.log`: JVM별 GC 원본 로그
- `results/validation.json`: 결과 완결성과 재계산 검증

전체 소스 코드와 재현 절차도 저장소에 같이 남겨뒀다.

- [batch-reader-benchmark-lab GitHub 저장소](https://github.com/kiy3035/batch-reader-benchmark-lab)

## 마치며

10만 건일 때 1.236~1.323배였던 차이는 100만 건에서 7.228~9.082배까지 벌어졌다.

EXPLAIN을 확인해 보니 OFFSET의 마지막 페이지는 1,000건을 반환하기 위해 100만 행을 통과했다. Keyset은 같은 위치에서도 `id > lastId`를 이용해 1,000행만 읽었다.

예상과 달랐던 부분도 있었다.

보조 인덱스를 켠 일부 Step은 더 빨랐지만 PostgreSQL은 실제로 그 보조 인덱스를 선택하지 않았다. Old Gen과 GC도 두 Reader의 큰 실행시간 차이를 설명할 정도로 다르지 않았다.

따라서 이번 결과를 `Keyset은 항상 9배 빠르다`거나 `보조 인덱스를 만들면 OFFSET이 25.3% 개선된다`고 정리하는 것은 맞지 않는다.

이번 프로젝트에서 확인한 범위는 여기까지다.

> 한 Windows 노트북의 Docker 환경, READY가 ID 앞쪽에 모인 합성 데이터, pageSize와 chunkSize 1,000, 가벼운 공통 Writer 조건에서 데이터가 커질수록 Keyset의 실행시간 증가가 OFFSET보다 완만했다.

실제 시스템에서는 상태 분포, 인덱스 선택도, 행 크기, 네트워크, DB 자원과 Writer I/O가 모두 달라진다.

그래도 한 가지는 분명하게 볼 수 있었다.

대량 Batch에서 페이지를 순서대로 끝까지 읽는다면 Reader를 단순히 익숙한 것으로 고르기 전에 마지막 페이지까지 가는 동안 DB가 몇 행을 통과하는지 직접 확인해 볼 필요가 있다.
