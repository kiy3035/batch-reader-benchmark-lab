# Batch Reader Benchmark Lab

Spring Batch에서 `LIMIT/OFFSET` 페이징과 Keyset 페이징의 특성을 로컬 PostgreSQL로 비교하기 위한 재현 프로젝트다. 현재는 **4단계 인덱스·EXPLAIN 수집 도구까지 완료**되어 있으며 전체 성능 실험은 아직 실행하지 않았다.

## 현재 구성

- Java 21, Spring Boot 3.5.16, Spring Batch 5.2.6, Gradle 8.12.1
- JPA/Hibernate와 PostgreSQL 16.15
- Docker Compose 기반 PostgreSQL
- Flyway 기반 `settlement_item` 스키마, 5건의 smoke seed
- PostgreSQL `generate_series` 기반 결정적 scale seed 함수
- DB와 seed를 확인하는 최소 Spring Batch Job
- 실제 `JpaPagingItemReader` OFFSET Reader와 restartable JPA Keyset Reader
- 두 Reader가 공유하는 chunk size 1000, checksum Processor/Writer 계약
- 파라미터 기반 단일 benchmark Job, wall-clock/건수/checksum/Old Gen 수집
- 고정 512MB heap/G1GC 독립 JVM 실행 스크립트와 CSV/JSON 결과 저장
- `(status, id)` 보조 인덱스 ON/OFF, 카탈로그 검증과 `ANALYZE` 자동화
- 앞·중간·마지막 페이지의 OFFSET/Keyset SQL, 바인딩과 EXPLAIN JSON 수집
- JUnit 5 단위 테스트와 Testcontainers PostgreSQL 통합 테스트

## 사전 조건

- Java 21
- Docker Desktop 또는 Docker Engine과 Compose v2

별도의 시스템 프로그램이나 유료 서비스는 필요하지 않다. Gradle은 저장소의 Wrapper가 내려받는다.

## 1단계 실행

PowerShell에서 PostgreSQL을 시작한다.

```powershell
docker compose up -d --wait postgres
```

애플리케이션을 실행하면 Flyway가 스키마와 smoke seed를 구성하고 `smokeJob`이 READY 4건을 확인한다.

```powershell
.\gradlew.bat bootRun --args="run.id=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
```

같은 DB에서 완료된 JobInstance를 다시 실행하려면 위 예시처럼 매번 다른 `run.id` JobParameter를 전달한다. 기본 접속값은 로컬 개발 전용이며 환경 변수로 바꿀 수 있다.

| 환경 변수 | 기본값 |
| --- | --- |
| `POSTGRES_PORT` | `5432` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/batch_benchmark` |
| `DB_USER` | `benchmark` |
| `DB_PASSWORD` | `benchmark_local` |

종료할 때 DB 컨테이너만 내리려면 다음 명령을 사용한다.

```powershell
docker compose down
```

로컬 데이터를 포함한 볼륨까지 지우려는 경우에만 `docker compose down -v`를 사용한다.

## 테스트

전체 테스트에는 Docker가 필요한 Testcontainers 통합 테스트가 포함된다.

```powershell
.\gradlew.bat test
```

Docker 없이 단위 테스트만 실행할 수 있다.

```powershell
.\gradlew.bat test --tests "dev.benchmark.batchreader.DeterministicSeedFormulaTest"
```

Reader 정합성 통합 테스트만 실행할 수도 있다.

```powershell
.\gradlew.bat test --tests "dev.benchmark.batchreader.ReaderCorrectnessIntegrationTest"
```

## Reader Job

두 Job 모두 `status = READY`, `id ASC`, pageSize/chunkSize 1000, 동일 Entity와 checksum 처리 로직을 사용한다.

```powershell
.\gradlew.bat bootRun --args="--spring.batch.job.name=offsetReaderJob run.id=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
.\gradlew.bat bootRun --args="--spring.batch.job.name=keysetReaderJob run.id=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
```

- OFFSET: Spring Batch `JpaPagingItemReader`가 JPQL과 `setFirstResult`/`setMaxResults`로 페이지를 이동한다.
- Keyset: `id > :lastId ORDER BY id` JPQL과 `setMaxResults(1000)`을 사용하고 커밋된 `lastId`를 Step `ExecutionContext`에 저장한다.
- Writer: 전체 ID나 Entity를 보관하지 않고 처리 건수와 checksum만 누적한다.

## 결정적 대량 seed 기반

아래 스크립트는 `prepare_settlement_seed` 함수를 호출한다. 함수는 Java 메모리에 Entity를 쌓지 않고 PostgreSQL 내부에서 READY 목표 건수와 그 10%의 COMPLETED 행을 생성한다. ID별 고객, 금액, 시각, 참조번호, payload는 항상 같은 식으로 계산된다.

```powershell
.\scripts\seed-scale.ps1 -ReadyRows 100000
```

```bash
./scripts/seed-scale.sh 100000
```

스크립트는 100,000 / 500,000 / 1,000,000만 허용하도록 준비됐다. 아직 전체 규모 seed와 성능 실험은 실행하지 않았다.

## 단일 benchmark run

먼저 DB에 해당 scale seed를 준비하고 JAR를 빌드한다. `benchmarkJob`은 Step 측정을 시작하기 전에 `indexMode`에 맞춰 고정 보조 인덱스를 생성하거나 삭제하고, `ANALYZE settlement_item`을 실행한 뒤 PostgreSQL 카탈로그의 실제 정의를 검증한다. 이 준비 시간은 Step wall-clock 시간에 포함되지 않는다.

```powershell
.\gradlew.bat bootJar
.\scripts\seed-scale.ps1 -ReadyRows 100000
.\scripts\run-benchmark.ps1 -ReaderType OFFSET -TargetRows 100000 -IndexMode OFF -Repetition 1
```

각 run은 `-XX:+UseG1GC -Xms512m -Xmx512m`을 사용한 별도 JVM에서 실행된다. 기본 50ms 간격으로 `G1 Old Gen`의 `MemoryPoolMXBean.getUsage().getUsed()`를 샘플링한다. 해당 pool이 없으면 peak를 0으로 대체하지 않고 `null`과 N/A 사유를 기록한다.

생성 파일:

- `results/runs/<run-id>.json`: run 전체 지표
- `results/raw-runs.csv`: 검증된 인덱스 상태를 포함한 재집계 가능한 원본 행
- `results/summary.csv`: Reader/scale/index별 성공 run의 평균·최소·최대·표준편차와 Old Gen 요약
- `results/gc/<run-id>.log`: JVM unified GC 원본 로그

`targetRows`와 실제 read/write 수가 다르면 `countValid=false`로 기록되며 summary에서 제외된다. 전체 scale 증가 배율과 Reader 간 비율은 5단계 전체 matrix 결과가 존재할 때 계산한다.

## 인덱스와 EXPLAIN 수집

대상 scale seed와 Boot JAR를 준비한 뒤 인덱스 모드별 실행계획을 수집한다.

```powershell
.\gradlew.bat bootJar
.\scripts\seed-scale.ps1 -ReadyRows 100000
.\scripts\collect-explain.ps1 -TargetRows 100000 -IndexMode OFF
.\scripts\collect-explain.ps1 -TargetRows 100000 -IndexMode ON
```

```bash
./gradlew bootJar
./scripts/seed-scale.sh 100000
./scripts/collect-explain.sh 100000 OFF
./scripts/collect-explain.sh 100000 ON
```

수집기는 실제 READY 건수가 `TargetRows`와 다르면 중단한다. 인덱스 작업과 `EXPLAIN ANALYZE`는 JDBC URL의 호스트가 `localhost`, `127.0.0.1`, `::1` 중 하나이고 DB 이름이 `batch_benchmark`일 때만 실행된다.

`results/explain/<scale>-<reader>-<index>-<position>.json`에는 다음 근거가 저장된다.

- 고정 인덱스 이름 `idx_settlement_item_status_id`와 카탈로그에서 읽은 실제 정의
- `FIRST`, `MIDDLE`, `LAST` 위치의 SQL과 이름별 바인딩 값
- 같은 페이지를 가리키는 OFFSET과 `correspondingLastId`
- PostgreSQL 원본 `FORMAT JSON` 결과
- 모든 plan node의 scan 유형, 예상/실제 rows, loops, rows removed, shared hit/read blocks와 sort 정보
- planning time과 execution time

EXPLAIN 실행시간은 Reader Step 시간과 별도 산출물에 기록하며 benchmark 평균에 포함하지 않는다.

## 현재 범위

다음 작업은 5단계의 100k/500k/1m 전체 36회 실험이다. 현재 3,001건 검증 시간과 실행계획은 기능 확인 자료이며 Reader 성능 수치로 사용할 수 없다. 블로그 초안은 실제 전체 측정이 끝난 뒤 6단계에서 작성한다.
