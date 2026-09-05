# 기술 결정 기록

## 버전 고정

- Java toolchain은 프로젝트 명세에 따라 21로 고정했다.
- Spring Boot 3.x의 현재 유지보수 버전인 3.5.16을 고정했다. 이 BOM이 Spring Batch 5.2.6과 Hibernate 6.6.53.Final을 관리한다.
- Gradle Wrapper는 Java 21과 Spring Boot 3.5 계열을 지원하는 8.12.1로 고정했다.
- PostgreSQL 이미지는 `latest` 대신 `postgres:16.15-alpine`을 고정했다. 테스트와 Compose가 같은 버전을 사용한다.

동적 버전을 쓰지 않아 새 환경에서도 의존성 변화로 실험 조건이 흔들리지 않게 했다.

## 스키마 소유권

실험 테이블, seed 함수, Spring Batch 메타데이터는 모두 Flyway가 관리하고 Hibernate는 `ddl-auto=validate`만 사용한다. Batch 5.2.6의 공식 PostgreSQL 스키마를 V1에 고정했으며 Boot의 반복 초기화는 끈다. 같은 DB에서 애플리케이션을 재실행해도 Flyway가 이미 적용한 메타데이터와 충돌하지 않는다. Spring Batch 버전을 올릴 때는 공식 migration과 이 스키마의 차이를 함께 검토해야 한다.

## smoke seed

Flyway V3에 READY 4건과 COMPLETED 1건을 넣었다. ID `3` 등을 의도적으로 비워 이후 Reader 테스트가 ID 연속성을 가정하지 않게 한다. 이 데이터는 애플리케이션 기동과 최소 Job을 빠르게 검증하기 위한 것이며 성능 결과가 아니다.

## 대량 데이터 생성 기반

`prepare_settlement_seed(ready_rows)` PostgreSQL 함수와 PowerShell/Bash 진입점을 선택했다. `generate_series`가 DB 안에서 bounded SQL 한 번으로 행을 만들므로 Java에서 최대 100만 Entity를 생성하거나 `saveAll`하지 않는다.

공식 scale에서 READY 건수는 인자와 정확히 같고, 비교 대상인 COMPLETED는 READY의 10%다. 다음 값은 ID로만 계산되어 같은 입력에서 동일하다.

- `customer_id`: `100000 + id % 10000`
- `amount`: `(id * 37 % 1000000) / 100`
- `settled_at`: 고정 기준 시각 + ID초
- `reference_no`, `payload`: ID를 포함한 고정 형식

함수는 테스트 편의를 위해 0부터 1,000,000까지 허용하지만 사용자용 scale 스크립트는 명세의 세 크기만 허용한다. 실제 대량 적재는 5단계에서 실행하고 원본 조건과 시간을 기록한다.

## 최소 Batch Job

1단계 `smokeJob`은 단일 tasklet Step에서 READY 행 수가 0보다 큰지 확인한다. Flyway, DataSource, Batch JobRepository와 트랜잭션이 함께 작동한다는 기동 검증용이다. Reader와 Processor/Writer는 공정한 정합성 설계가 필요한 2단계에서 구현한다.

## 로컬 설정

Compose 기본 계정 값은 `.env.example`에 공개 가능한 로컬 예시로만 둔다. 실제 비밀값이나 외부 서비스는 사용하지 않는다. 데이터 volume은 Git에서 제외하며 OS page cache 초기화나 관리자 권한 작업은 수행하지 않는다.

## 2단계 Reader와 체크포인트

- OFFSET은 비교 대상 자체인 Spring Batch `JpaPagingItemReader`를 그대로 사용한다. JPQL은 상태 조건과 고유 ID 오름차순만 포함하며 pageSize와 saveState를 명시했다.
- Keyset은 `ItemStreamReader`와 JPA page source를 분리했다. page source는 페이지마다 영속성 컨텍스트를 비우고 `id > :lastId ORDER BY id`에 `setMaxResults(1000)`을 적용한다.
- Keyset의 `lastId`는 항목을 정상 반환할 때 갱신하고, Spring Batch가 chunk commit과 함께 호출하는 `update`에서 Step ExecutionContext에 기록한다. 실패한 chunk의 ExecutionContext 변경은 트랜잭션과 함께 롤백된다.
- 두 Step은 단일 스레드이며 chunk/page size 1000과 같은 Entity, Processor, Writer 계약을 사용한다.

checksum 기여값은 `id * 31 + 센트 단위 amount`다. Writer는 전체 ID나 Entity를 메모리에 보관하지 않고 count와 checksum만 유지한다. ID 순서와 중복 검사는 작은 자동 테스트 데이터에서만 목록을 수집한다.

## 3단계 측정 도구

- 단일 `benchmarkJob`이 `readerType`, `targetRows`, `indexMode`, `repetition`, `runId`, `status`, `gcLogPath`를 받는다. 별도 OFFSET/Keyset Job은 smoke와 직접 실행 호환성을 위해 유지한다.
- Step listener의 `System.nanoTime()` 구간을 wall-clock duration으로 사용하고 시작/종료 `Instant`도 함께 기록한다.
- Old Gen은 50ms 간격의 daemon sampler가 `G1 Old Gen` MXBean 사용량을 읽는다. pool이 없으면 peak는 null이고 측정 상태에 N/A 사유가 남는다.
- PowerShell/Bash runner는 run마다 별도 Java 프로세스를 시작하며 G1GC와 512MB 고정 heap, unified GC log를 명시한다.
- JSON run 파일을 원본으로 summary를 매번 다시 계산한다. `COMPLETED`이면서 target count가 맞는 run만 집계한다. raw CSV에는 실패와 count mismatch도 보존한다.

3단계의 `indexMode`는 요청 조건을 기록하는 파라미터다. 실제 `(status, id)` 보조 인덱스 전환과 카탈로그 검증은 4단계에서 연결하므로, 그 전의 출력은 정식 인덱스 비교 결과로 해석하지 않는다.

## 4단계 인덱스와 EXPLAIN

- 실험 보조 인덱스 이름은 `idx_settlement_item_status_id`, 정의는 `btree (status, id)`로 고정했다. PK와 그 인덱스는 변경하지 않는다.
- `benchmarkJob`의 Job listener가 Step 전에 ON/OFF DDL, `ANALYZE settlement_item`, 카탈로그 검증을 수행한다. 따라서 Step listener가 재는 Reader 실행시간에서 인덱스 준비 비용을 분리한다.
- ON 검증은 이름만 보지 않고 PostgreSQL 카탈로그에서 컬럼 순서, valid/ready, non-unique 조건과 실제 `pg_get_indexdef`를 확인한다. OFF는 해당 이름의 보조 인덱스가 존재하지 않아야 한다.
- 인덱스 변경과 `EXPLAIN ANALYZE`는 로컬 호스트의 `batch_benchmark` 데이터베이스로 제한했다. 잘못 설정한 원격 또는 다른 이름의 DB에는 실험성 DDL을 실행하지 않는다.
- 페이지 위치는 pageSize 1000 경계의 첫 페이지, 중앙 페이지, 마지막 페이지로 계산한다. OFFSET 직전 행의 실제 ID를 조회해 같은 위치의 Keyset `lastId`로 기록하므로 ID gap이 있어도 대응 관계가 유지된다.
- SQL의 placeholder와 이름별 실제 바인딩, PostgreSQL 원본 `FORMAT JSON`을 함께 보존한다. 별도로 scan/node 유형, rows, loops, rows removed by filter, shared hit/read blocks, sort, planning/execution time을 평탄화해 후속 집계가 원본 JSON 파서에 종속되지 않게 했다.
- 4단계 검증은 READY 3,001건의 소규모 합성 데이터로만 수행했다. 이때의 plan 선택과 시간은 기능 검증 근거이며 5단계 성능 결과로 사용하지 않는다.

## 5단계 전체 측정 설계

- scale마다 결정적 seed를 한 번 준비하고 그 snapshot을 12개 측정 run이 공유한다. Reader 실행 중에는 행을 변경하지 않으며 index DDL만 조건에 따라 전환한다.
- 각 scale의 OFFSET/Keyset × index OFF/ON 네 조건을 한 번씩 warm-up한다. warm-up 결과는 `build/stage5-warmup/`에 분리해 공식 raw/summary에 포함하지 않는다.
- 반복 1과 3은 OFFSET→KEYSET, 반복 2는 KEYSET→OFFSET 순서다. index 순서도 OFF→ON, ON→OFF, OFF→ON으로 교차하며 전체 순번을 run JSON, raw CSV와 `execution-order.csv`에 기록한다.
- 모든 측정 run은 같은 Boot JAR를 사용하는 별도 JVM이다. G1GC, `-Xms512m -Xmx512m`, unified GC log와 50ms Old Gen sampling을 고정한다.
- 기본 결과는 명시적 warm-up 뒤의 warm-cache 로컬 비교다. 관리자 권한이 필요한 OS page cache 초기화는 하지 않으며 cold-cache 결과라고 표현하지 않는다.
- run 결과에는 나노초·밀리초·초, JVM 옵션, GC 로그와 scale/Reader/index별 EXPLAIN glob을 함께 기록한다. 성공, count/checksum, 인덱스 검증과 원본 파일 존재 여부를 자동 검사한다.
- summary는 성공하고 count가 맞으며 인덱스 상태가 검증된 run만 사용한다. 평균·최소·최대·모표준편차와 Old Gen 평균/최대 외에 500k/100k, 1m/100k, 1m/500k 증가 배율과 같은 조건의 OFFSET/Keyset 평균 비율을 계산한다.
- 환경 파일은 측정 전에 깨끗한 Git 커밋의 SHA와 OS, CPU, RAM, Java, Gradle, Spring, Hibernate, PostgreSQL, Docker 버전 및 자원 할당을 기록한다.

## 5단계 실제 실행

- 측정 코드를 먼저 커밋하고 그 SHA `26e9f85761583affa1ac963ff1f83dfd316d05af`에서 Boot JAR를 한 번 빌드해 모든 run에 사용했다.
- 공식 결과 디렉터리를 비운 뒤 scale별 seed, 조건별 warm-up, EXPLAIN 수집과 36개 측정을 한 흐름으로 실행했다. warm-up 산출물은 Git에서 제외되는 `build/stage5-warmup/`에 두었다.
- 첫 준비 실행에서 쉼표가 포함된 전체 `-Xlog` 문자열을 Spring Batch JobParameter 값으로 전달하자 Batch의 타입 구분자로 해석되는 오류가 발생했다. 공식 run은 시작되지 않았다. run 결과에는 안전하게 G1GC와 heap 옵션만 기록하고, 전체 GC logging 형식과 실제 로그 경로는 환경 파일과 run의 `gcLogPath`로 보존하기로 했다.
- 공식 36개 run은 모두 `COMPLETED`, `countValid=true`, `indexVerified=true`였다. scale별 checksum도 두 Reader와 모든 인덱스·반복 조건에서 같았다.
- `validate-results.ps1`은 파일 개수와 연결뿐 아니라 원본 36개 JSON에서 평균, 초 단위 값, 최소·최대, 모표준편차, 규모 증가 배율, OFFSET/Keyset 비율과 Old Gen 요약을 다시 계산해 CSV와 비교한다.
- 측정이 끝난 뒤 전용 Compose 컨테이너, network와 volume을 제거했다. 공식 결과에는 DB 비밀번호나 임시 애플리케이션 로그를 포함하지 않는다.

## 6단계 문서와 파생 데이터

- `runs/*.json`을 공식 run의 기준 원본으로 유지한다. `raw-runs.csv`는 감사용 평탄화 파일, `summary.csv`는 12개 조건의 통계와 그래프 입력용 파생 파일로 정의했다. 평균만 남겨 원본 편차가 사라지는 것을 막기 위해 세 파일을 모두 보존한다.
- `build-report-data.ps1`이 summary, 36개 EXPLAIN JSON과 36개 GC 로그에서 `results/report/*.csv`를 재생성한다. 보고서 표가 수작업으로 복사한 값에만 의존하지 않게 했다.
- EXPLAIN의 대표 scan은 `relationName=settlement_item`인 node로 고른다. `scanActualRows / returnedRows`를 함께 기록해 후반 OFFSET이 반환량보다 얼마나 많은 행을 통과시켰는지 비교한다.
- GC 로그는 애플리케이션 부팅부터 종료까지의 JVM 전체 범위로 명시하고 컬럼에도 `process` 접두어를 붙였다. Step 구간 메모리는 50ms MXBean `peakOldGenBytes`만 사용해 두 측정 범위를 혼동하지 않는다.
- 공식 EXPLAIN 36개가 모두 `settlement_item_pkey`를 선택했으므로 인덱스 ON/OFF Step 시간 차이를 보조 인덱스 사용 효과로 해석하지 않는다. READY가 선행 ID 구간에 몰린 seed 분포와 세 번의 반복, warm-cache 로컬 환경을 주요 한계로 공개한다.
- 블로그의 결론은 실제 36개 run과 공식 EXPLAIN/GC에서 확인한 값만 사용한다. 3,001건 기능 검증 시간이나 측정하지 않은 누적 scan 수는 성능 근거로 인용하지 않는다.
