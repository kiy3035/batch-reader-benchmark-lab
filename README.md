# Batch Reader Benchmark Lab

Spring Batch에서 `LIMIT/OFFSET` 페이징과 Keyset 페이징의 특성을 로컬 PostgreSQL로 비교하기 위한 재현 프로젝트다. 현재는 **2단계 Reader 구현과 정합성 검증까지 완료**되어 있으며 성능 측정 결과는 아직 없다.

## 현재 구성

- Java 21, Spring Boot 3.5.16, Spring Batch 5.2.6, Gradle 8.12.1
- JPA/Hibernate와 PostgreSQL 16.15
- Docker Compose 기반 PostgreSQL
- Flyway 기반 `settlement_item` 스키마, 5건의 smoke seed
- PostgreSQL `generate_series` 기반 결정적 scale seed 함수
- DB와 seed를 확인하는 최소 Spring Batch Job
- 실제 `JpaPagingItemReader` OFFSET Reader와 restartable JPA Keyset Reader
- 두 Reader가 공유하는 chunk size 1000, checksum Processor/Writer 계약
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

스크립트는 100,000 / 500,000 / 1,000,000만 허용하도록 준비됐지만, **1단계에서는 전체 규모 seed와 성능 실험을 실행하지 않았다**.

## 현재 범위

인덱스 전환, 벤치마크 지표 수집, EXPLAIN, GC/Old Gen 측정, 전체 36회 실험과 블로그 초안은 다음 단계 작업이다. 현재 테스트 시간은 Reader 성능 수치로 사용할 수 없다.
