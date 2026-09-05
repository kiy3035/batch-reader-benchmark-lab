# 프로젝트 진행 상황

마지막 갱신: 2026-09-05 (Asia/Seoul)

## 1. 완료한 작업

- Java 21 toolchain, Spring Boot 3.5.16, Spring Batch 5.2.6, Gradle 8.12.1 프로젝트 구성
- Spring Data JPA, Flyway, PostgreSQL 드라이버 구성
- `postgres:16.15-alpine` Docker Compose와 healthcheck 구성
- Flyway Spring Batch 5.2.6 메타데이터, `settlement_item` 스키마와 READY 4건 / COMPLETED 1건 smoke seed 작성
- PostgreSQL `generate_series` 기반 결정적 `prepare_settlement_seed` 함수 작성
- 100k/500k/1m을 받는 PowerShell 및 Bash seed 진입점 작성
- READY 데이터와 DB 연동을 확인하는 단일 Step `smokeJob` 작성
- 단위 및 Testcontainers 통합 테스트 작성
- 1단계 실행 방법과 결정 사항 문서화
- `SettlementItem` JPA Entity와 공통 checksum Processor/Writer 구현
- 실제 Spring Batch `JpaPagingItemReader` 기반 OFFSET Reader 구현
- `id > :lastId`와 `setMaxResults(1000)`을 사용하는 Keyset `ItemStreamReader` 구현
- 두 Reader에 동일한 status, ID 정렬, chunk/page size 1000과 단일 스레드 Step 적용
- Keyset `lastId`의 Step ExecutionContext 저장 및 동일 JobInstance 재시작 구현
- 경계 크기, ID gap, 혼합 상태, SQL 형태, checksum, 누락·중복·정렬 검증 작성

## 2. 실제 실행한 테스트와 결과

### 자동 테스트

실행 명령:

```powershell
.\gradlew.bat --no-daemon test
```

최종 clean 실행 결과: `BUILD SUCCESSFUL` (2026-09-05), 29초.

- `DeterministicSeedFormulaTest`: 1개 통과
- `StageOneIntegrationTest`: 3개 통과
- 총 4개 테스트, 실패 0개, 오류 0개, 건너뜀 0개
- 실제 Testcontainers PostgreSQL 16.15에서 Flyway 4개 migration, smoke seed, 결정적 seed 재생성, Batch Job 완료를 검증

### 2단계 Reader 정합성 테스트

최종 실행 명령:

```powershell
.\gradlew.bat --no-daemon clean test
```

최종 결과: `BUILD SUCCESSFUL` (2026-09-05), 35초. 총 12개, 실패 0개, 오류 0개, 건너뜀 0개.

- `DeterministicSeedFormulaTest`: 1개 통과
- `KeysetJpaItemReaderTest`: 3개 통과
- `ReaderCorrectnessIntegrationTest`: 5개 통과
- `StageOneIntegrationTest`: 3개 통과

검증 항목:

- Keyset 단위 테스트: 빈 결과, 여러 페이지, 마지막 부분 페이지, ID gap, lastId 저장/복원
- PostgreSQL 통합 테스트: READY 0/1/999/1000/1001건에서 OFFSET/Keyset count, ID 순서, 중복, checksum 일치
- READY 이외 상태가 섞이고 ID gap이 있는 데이터에서 두 Reader 결과 일치
- Hibernate 실제 SQL에서 OFFSET의 `offset ? rows fetch first ? rows only`와 Keyset의 `id>? ... fetch first ? rows only` 확인
- 1001건에서 OFFSET SQL이 여러 페이지 실행되고 Reader page가 2 이상 증가함을 확인
- 두 번째 chunk에서 의도적으로 실패한 Keyset Job의 체크포인트가 `lastId=1000`인지 확인
- 같은 JobInstance 재시작 뒤 ID 1~1001이 누락·중복 없이 한 번씩 커밋되는지 확인
- OFFSET Reader도 두 번째 chunk 실패 뒤 같은 JobInstance에서 ID 1~1001을 정확히 한 번 커밋하는지 확인

### 애플리케이션과 Compose 통합 실행

- Boot JAR 빌드 성공
- 별도 Compose project와 사용 가능한 임시 포트 `65432`에서 PostgreSQL 16.15 healthcheck 성공
- Flyway migration V1~V4 적용 성공
- Spring Boot 3.5.16 / Java 21.0.8 애플리케이션 기동 성공
- 같은 DB에서 서로 다른 `run.id`로 애플리케이션을 2회 연속 기동하고 완료된 Job 2건 확인
- 첫 `smokeJob` / `smokeStep` 상태 `COMPLETED`, 실행시간 124ms / 46ms
- 둘째 `smokeJob` / `smokeStep` 상태 `COMPLETED`, 실행시간 107ms / 41ms
- DB 조회 결과 READY 4건, COMPLETED 1건 확인
- 검증 후 임시 컨테이너, network, volume 정리 완료

### 2단계 프로덕션 JAR 실행

- 별도 PostgreSQL 16.15 Compose DB에서 `offsetReaderJob`과 `keysetReaderJob`을 각각 실행
- 두 Job 모두 `COMPLETED`, readCount 4, writeCount 4, commitCount 1 확인
- smoke dataset의 READY 4건을 같은 조건과 처리 흐름으로 읽음
- 확인된 시간은 기동 검증 로그이며 성능 비교 결과로 사용하지 않음
- 검증 후 임시 컨테이너, network, volume 정리 완료

## 3. 현재 정상 동작하는 기능

- Gradle Wrapper를 통한 빌드와 테스트
- Docker Compose PostgreSQL 기동과 영속 volume
- Flyway의 합성 정산 스키마 및 smoke 데이터 초기화
- Flyway 기반 Spring Batch 메타데이터 초기화와 최소 Job 반복 실행
- 작은 입력에서 같은 결과를 두 번 만드는 결정적 seed 함수
- scale seed 실행용 PowerShell/Bash 인터페이스
- OFFSET/Keyset Reader Job과 공통 Processor/Writer
- Keyset 페이지별 영속성 컨텍스트 정리와 restart checkpoint
- Reader 경계값 및 실제 PostgreSQL 정합성 자동 검증

## 4. 미완료 작업과 측정 대기 항목

- 3단계 벤치마크 파라미터와 실행시간/건수/checksum/Old Gen 수집
- 4단계 보조 인덱스 ON/OFF와 실제 SQL/EXPLAIN 수집
- 5단계 100k/500k/1m 전체 36회 측정과 결과 집계
- 6단계 아키텍처·방법론·결과 문서와 실제 측정값 기반 블로그 초안
- `results/` 산출물은 아직 생성하지 않음

## 5. 발생한 오류와 확인된 원인

- 첫 Compose 수동 검증에서 호스트 포트 `55433`이 이미 사용 중이어서 bind에 실패했다. 해당 임시 리소스를 정리하고 비어 있던 `65432`를 선택해 재실행했다.
- 첫 DB 확인 SQL에서 `batch_job_execution`에 직접 존재하지 않는 `job_name` 컬럼을 조회해 SQL 오류가 발생했다. 애플리케이션 로그에서 Job `COMPLETED`가 확인됐고, 자동 통합 테스트에서도 `JobExecution`과 Step 상태를 검증했다. 다음 메타데이터 조회는 `batch_job_instance`와 join해야 한다.
- 일반 sandbox에서 Docker 설정 파일과 네트워크 접근이 제한되어, 승인된 로컬 Docker 및 공개 Maven 의존성 명령으로 검증했다. 별도 프로그램 설치나 관리자 권한 작업은 수행하지 않았다.
- 최초 재시작 테스트에서 실패 플래그를 첫 chunk가 먼저 소비해 의도한 두 번째 chunk 실패가 발생하지 않았다. ID 1001이 포함된 chunk에서만 atomic flag를 전환하도록 테스트를 수정했고, `FAILED → 동일 JobInstance 재시작 → COMPLETED`를 확인했다.

## 6. 다음 작업에서 바로 시작할 내용

사용자가 `계속 진행해`라고 요청하면 문서와 이 진행 기록을 다시 읽고 3단계만 수행한다.

1. Reader 종류, 데이터 크기와 실행 식별자를 JobParameter로 선택하는 단일 벤치마크 진입점
2. Step wall-clock 시간, 처리 건수, checksum, 실패 상태 수집
3. 고정 heap/G1GC 실행 스크립트와 run별 GC 로그 경로 구성
4. `G1 Old Gen` MemoryPoolMXBean 고정 주기 sampler와 peak 집계
5. raw CSV/JSON 및 요약 산출 구조와 집계 단위 테스트

## 7. 실행 및 재현 명령어

```powershell
docker compose up -d --wait postgres
.\gradlew.bat test
.\gradlew.bat bootRun --args="run.id=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
.\gradlew.bat bootRun --args="--spring.batch.job.name=offsetReaderJob run.id=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
.\gradlew.bat bootRun --args="--spring.batch.job.name=keysetReaderJob run.id=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
docker compose down
```

결정적 scale seed 기반은 준비됐지만 이번 단계에서는 실행하지 않았다.

```powershell
.\scripts\seed-scale.ps1 -ReadyRows 100000
```

## 8. 변경한 주요 파일

- `build.gradle`, `settings.gradle`, Gradle Wrapper
- `compose.yaml`, `.env.example`, `.gitignore`
- `src/main/resources/application.yml`
- `src/main/resources/db/migration/V1__create_spring_batch_metadata.sql`
- `src/main/resources/db/migration/V2__create_settlement_item.sql`
- `src/main/resources/db/migration/V3__insert_smoke_seed.sql`
- `src/main/resources/db/migration/V4__create_deterministic_seed_function.sql`
- `src/main/java/dev/benchmark/batchreader/BatchReaderBenchmarkApplication.java`
- `src/main/java/dev/benchmark/batchreader/batch/SmokeJobConfiguration.java`
- `scripts/seed-scale.ps1`, `scripts/seed-scale.sh`
- 단위 및 통합 테스트
- `README.md`, `docs/decisions.md`, `PROGRESS.md`
- `src/main/java/dev/benchmark/batchreader/domain/SettlementItem.java`
- `src/main/java/dev/benchmark/batchreader/reader/`의 OFFSET/Keyset Reader 구성
- `src/main/java/dev/benchmark/batchreader/batch/ReaderJobConfiguration.java`
- 공통 checksum Processor/Writer와 contribution record
- `KeysetJpaItemReaderTest`, `ReaderCorrectnessIntegrationTest`, SQL inspector

## 9. 생성된 측정 결과 파일 경로

없음. 1단계에서는 성능 측정, EXPLAIN, GC 로그와 블로그 결과 수치를 생성하지 않았다.

## 검증 환경

| 항목 | 실제 확인값 |
| --- | --- |
| CPU | 11th Gen Intel Core i5-1135G7 @ 2.40GHz |
| Host RAM | 8,379,490,304 bytes |
| OS | Microsoft Windows 11 Home 64-bit, 10.0.26200 |
| Java/JVM | Eclipse Temurin OpenJDK 21.0.8+9, HotSpot 64-Bit Server VM |
| Spring Boot | 3.5.16 |
| Spring Batch | 5.2.6 |
| PostgreSQL | 16.15 Alpine container |
| Docker Engine | 24.0.7 |
| Docker Compose | v2.23.3-desktop.2 |
| Docker 할당 | 8 vCPU, 3,998,879,744 bytes RAM |
