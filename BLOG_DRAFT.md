# Spring Batch에서 100만 건을 읽었더니: OFFSET 177초, Keyset 19.5초

정산처럼 많은 행을 ID 순서대로 끝까지 읽는 Batch Job을 만들 때 `JpaPagingItemReader`는 익숙하고 편하다. JPQL과 pageSize를 지정하면 Spring Batch가 페이지 상태까지 관리한다. 그런데 이 Reader가 사용하는 LIMIT/OFFSET은 뒤 페이지로 갈수록 앞의 행을 건너뛴다.

그 비용이 전체 Job에서 실제로 얼마나 커지는지 로컬 환경에서 확인했다. 같은 합성 데이터를 실제 `JpaPagingItemReader`와 `WHERE id > :lastId` Keyset Reader로 읽고, 10만·50만·100만 건, 보조 인덱스 ON/OFF, 조건별 3회를 실행했다. 실행시간뿐 아니라 실제 PostgreSQL EXPLAIN, G1GC 로그, Step 구간 Old Gen peak와 checksum도 함께 남겼다.

먼저 결과부터 보면 100만 건의 평균은 다음과 같았다.

| Reader | 보조 인덱스 OFF | 보조 인덱스 ON |
| --- | ---: | ---: |
| JpaPagingItemReader OFFSET | 177.140초 | 132.354초 |
| Keyset | 19.504초 | 18.310초 |
| OFFSET / Keyset | 9.082배 | 7.228배 |

이 수치는 한 Windows 노트북의 Docker Desktop에서 얻은 결과다. 모든 상황에서 Keyset이 이 배율로 빠르다는 뜻은 아니다. 이번 실험이 보여 준 핵심은 데이터가 늘 때 두 방식의 비용이 어떤 모양으로 증가했고, 그 차이가 실제 실행계획에서 어떻게 나타났는가다.

## 두 Reader를 같은 조건에 놓기

비교 대상은 다음 두 쿼리 형태다.

```sql
-- OFFSET
SELECT ...
FROM settlement_item
WHERE status = 'READY'
ORDER BY id
LIMIT 1000 OFFSET :offset;
```

```sql
-- Keyset
SELECT ...
FROM settlement_item
WHERE status = 'READY'
  AND id > :lastId
ORDER BY id
LIMIT 1000;
```

OFFSET 쪽은 직접 흉내 낸 Reader가 아니라 Spring Batch의 실제 `JpaPagingItemReader`를 사용했다. JPQL에는 LIMIT를 넣지 않고 `pageSize=1000`을 지정했다. Hibernate가 `setFirstResult`와 `setMaxResults`를 PostgreSQL LIMIT/OFFSET으로 변환한다.

Keyset Reader는 현재 페이지의 마지막 ID를 다음 쿼리의 조건으로 사용한다. 한 페이지를 소진하면 아래 JPQL에 `setMaxResults(1000)`을 적용한다.

```java
SELECT s
FROM SettlementItem s
WHERE s.status = :status
  AND s.id > :lastId
ORDER BY s.id ASC
```

두 Reader에는 다음 조건을 공통으로 적용했다.

- `status = READY`, `id ASC`
- pageSize와 chunkSize 1000
- 같은 JPA Entity와 조회 컬럼
- 같은 checksum Processor와 메모리 집계 Writer
- 단일 스레드 Step
- G1GC, `-Xms512m -Xmx512m`
- Reader 실행마다 별도 JVM

Writer는 외부 DB에 다시 쓰지 않는다. ID와 amount로 계산한 checksum과 count만 누적해 Reader 외의 I/O가 비교를 덮지 않게 했다. 전체 Entity나 ID 목록도 저장하지 않는다.

## 결정적 합성 데이터와 실행 순서

PostgreSQL `generate_series`로 READY 100,000 / 500,000 / 1,000,000건과 그 10%의 COMPLETED 행을 만들었다. 고객 ID, 금액, 시각, 참조번호와 JSON payload는 ID만으로 계산한다. 같은 scale을 다시 만들면 같은 행과 checksum이 나온다.

각 scale에서 OFFSET/Keyset × 보조 인덱스 OFF/ON의 네 조건을 한 번씩 warm-up한 뒤 공식 측정을 실행했다. warm-up은 평균에서 제외했다. 반복 순서는 OFFSET→Keyset, Keyset→OFFSET, OFFSET→Keyset으로 교차했고 인덱스 순서도 OFF→ON, ON→OFF, OFF→ON으로 바꿨다.

OS page cache는 강제로 비우지 않았다. 관리자 권한 없이 cold cache를 보장할 수 없으므로 이 결과는 명시적인 warm-up 뒤의 warm-cache 비교다. 실제 실행 순서는 `results/execution-order.csv`에 남겼다.

## 데이터가 늘수록 벌어진 실행시간

세 번의 Step wall-clock 평균은 다음과 같았다.

| READY 행 | OFFSET OFF | Keyset OFF | OFFSET/Keyset | OFFSET ON | Keyset ON | OFFSET/Keyset |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100k | 5.924초 | 4.794초 | 1.236배 | 7.346초 | 5.553초 | 1.323배 |
| 500k | 48.620초 | 12.105초 | 4.017배 | 41.703초 | 13.437초 | 3.104배 |
| 1m | 177.140초 | 19.504초 | 9.082배 | 132.354초 | 18.310초 | 7.228배 |

100k에서는 1초대 차이였지만 1m에서는 114~158초 차이가 났다. 증가 배율로 보면 더 분명하다.

| Reader | Index | 500k / 100k | 1m / 100k | 1m / 500k |
| --- | --- | ---: | ---: | ---: | ---: |
| OFFSET | OFF | 8.208배 | 29.903배 | 3.643배 |
| OFFSET | ON | 5.677배 | 18.018배 | 3.174배 |
| Keyset | OFF | 2.525배 | 4.068배 | 1.611배 |
| Keyset | ON | 2.420배 | 3.298배 | 1.363배 |

행 수는 100k에서 1m으로 10배가 됐지만 OFFSET 시간은 18.0~29.9배가 됐다. Keyset은 3.3~4.1배였다. 둘 다 Entity 매핑과 Processor 비용을 지불하지만 OFFSET에는 후반 페이지로 갈수록 커지는 건너뛰기 비용이 추가된다.

반복 편차도 같이 봐야 한다. 1m OFFSET OFF는 153.858~219.096초, 모표준편차 29.727초였다. 같은 조건의 Keyset은 18.933~20.549초, 표준편차 0.740초였다. OFFSET OFF의 가장 빠른 run도 Keyset의 가장 느린 run보다 길었지만, 세 번뿐인 측정에서 작은 차이까지 일반화할 수는 없다.

## EXPLAIN에서 보인 후반 페이지 비용

1m 데이터의 FIRST, MIDDLE, LAST 페이지를 실제 바인딩 값으로 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` 했다.

| Index | Reader | 위치 | scanActualRows | 반환 행 | 실행시간 |
| --- | --- | --- | ---: | ---: | ---: |
| OFF | OFFSET | FIRST | 1,000 | 1,000 | 0.370ms |
| OFF | OFFSET | MIDDLE | 501,000 | 1,000 | 131.198ms |
| OFF | OFFSET | LAST | 1,000,000 | 1,000 | 220.363ms |
| OFF | Keyset | FIRST | 1,000 | 1,000 | 0.290ms |
| OFF | Keyset | MIDDLE | 1,000 | 1,000 | 0.387ms |
| OFF | Keyset | LAST | 1,000 | 1,000 | 0.649ms |

OFFSET LAST는 마지막 1000건을 반환하기 위해 scan node에서 100만 행을 통과시켰다. MIDDLE도 50만 건을 건너뛴 뒤 1000건을 반환하므로 501,000행이었다. Keyset은 세 위치 모두 `id > lastId`를 PK의 Index Cond로 사용했고 scanActualRows가 1000이었다.

buffer에서도 차이가 보였다. 1m LAST의 OFFSET OFF는 shared read 23,119 blocks였다. Keyset OFF는 shared hit 26 + read 2 blocks였다. cache에 이미 있는 block과 디스크에서 읽은 block의 구성은 실행 시점에 따라 달라질 수 있지만, 후반 OFFSET이 훨씬 많은 block에 접근한 사실은 원본 plan에 남아 있다.

## 보조 인덱스 ON이 빨라졌다고 말할 수 없는 이유

실험에서 켜고 끈 인덱스는 `(status, id)`다. 1m OFFSET 평균은 OFF 177.140초, ON 132.354초여서 ON이 25.3% 짧았다. 이 숫자만 보면 보조 인덱스 덕분이라고 결론 내리기 쉽다.

하지만 36개 공식 EXPLAIN은 ON/OFF 모두 보조 인덱스가 아닌 `settlement_item_pkey`를 선택했다. 1m LAST 쿼리 한 번의 실행시간은 오히려 ON 232.742ms, OFF 220.363ms였다. Step 평균과 단일 EXPLAIN의 방향도 일치하지 않았다.

이번 seed는 READY 행을 ID 앞부분에 연속 배치한다. sampled READY 페이지에서는 `Rows Removed by Filter`도 모두 0이었다. 보조 인덱스가 유리할 수 있는 상태 선택도가 높은 분포를 충분히 만들지 못했다.

그래서 이 결과로 “보조 인덱스가 OFFSET을 25.3% 개선했다”고 말하지 않는다. 확인된 사실은 인덱스 모드별 Step 시간이 달랐지만 선택된 scan은 모두 PK였다는 것이다. 로컬 cache, Docker와 시스템 부하, 세 번의 반복 편차가 남아 있다. 상태가 섞인 데이터나 낮은 READY 비율은 별도 실험이 필요하다.

## Old Gen과 GC는 큰 차이가 없었다

Step 실행 중 `MemoryPoolMXBean`의 `G1 Old Gen`을 50ms마다 샘플링했다.

| READY 행 | Keyset OFF / ON | OFFSET OFF / ON |
| ---: | ---: | ---: |
| 100k | 0 / 0 MiB | 0 / 0 MiB |
| 500k | 2.305 / 2.292 MiB | 2.304 / 2.333 MiB |
| 1m | 2.317 / 2.313 MiB | 2.304 / 2.321 MiB |

100k의 0은 sampler 실패가 아니라 Step 구간에 관찰된 Old Gen 사용량이 0이었다는 뜻이다. 500k와 1m은 모두 약 2.3MiB로 Reader 간 차이가 작았다. 두 Reader 모두 한 번에 페이지와 chunk 1000개만 유지하고, Writer도 전체 결과를 모으지 않기 때문이다.

Unified GC 로그는 Step뿐 아니라 JVM 부팅부터 종료까지를 포함한다. 모든 run에서 G1과 512MiB heap을 확인했고 Full GC와 OOM은 없었다. 전체 프로세스 pause 횟수는 Reader와 인덱스에 관계없이 100k 12회, 500k 16회, 1m 21회였다.

1m OFFSET OFF의 평균 pause 합계가 288.607ms로 가장 컸지만 Step 평균 177.140초와 비교하면 작다. Keyset OFF의 pause 합계는 198.961ms, Step은 19.504초였다. 이번 실행에서는 수십~백여 초의 Reader 시간 차이를 GC pause로 설명하기 어렵다.

## 재시작과 정합성

빠른 Reader라도 누락이나 중복이 있으면 사용할 수 없다. Keyset Reader는 마지막으로 정상 반환한 ID를 chunk commit과 함께 `ExecutionContext`에 저장한다. 실패한 chunk의 checkpoint는 롤백되며 같은 JobInstance를 재시작하면 마지막 커밋 ID 다음부터 읽는다.

자동 테스트에서는 ID gap, 빈 결과, 999/1000/1001건과 마지막 부분 페이지를 확인했다. 1001건의 두 번째 chunk에서 의도적으로 실패시킨 뒤 재시작해 두 Reader 모두 각 ID를 정확히 한 번 커밋하는지도 검증했다.

공식 36개 run의 read/write 수는 모두 목표 건수와 같았다. scale별 checksum도 Reader와 인덱스 조건에 관계없이 일치했다.

| READY 행 | checksum |
| ---: | ---: |
| 100k | 202,165,400,000 |
| 500k | 4,121,634,000,000 |
| 1m | 16,000,015,000,000 |

## 언제 Keyset을 선택할까

이 실험처럼 고유하고 불변인 ID를 따라 대량 데이터를 처음부터 끝까지 순차 처리한다면 Keyset은 후반 페이지의 skip 비용을 피할 수 있다. 특히 데이터가 커질수록 이번 결과에서는 차이가 확대됐다.

대신 Keyset은 정렬 키가 고유하고 안정적이어야 하고, 방향이 정해진 순차 탐색에 맞는다. 재시작 checkpoint도 명시적으로 관리해야 한다. 임의의 페이지 번호로 바로 이동하거나 복잡한 사용자 정렬이 필요한 조회에는 OFFSET이 더 단순할 수 있다. 작은 데이터에서는 이 단순성이 성능 차이보다 중요할 수도 있다.

이번 결과에서 말할 수 있는 범위는 명확하다. 한 로컬 환경, READY가 ID 앞에 모인 합성 데이터, page/chunk 1000, 가벼운 공통 Writer 조건에서 Keyset의 실행시간 증가가 OFFSET보다 완만했다. 실제 시스템에서는 상태 분포, 인덱스 선택도, 행 크기, DB 자원, 네트워크와 Writer I/O를 다시 측정해야 한다.

전체 코드와 재현 명령, 36개 원본 run, EXPLAIN JSON과 GC 로그는 저장소에 함께 둔다. 평균 표는 `results/summary.csv`, 원본은 `results/raw-runs.csv`와 `results/runs/*.json`이며 `scripts/validate-results.ps1`로 summary를 다시 계산할 수 있다.
