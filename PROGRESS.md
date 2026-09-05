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

## 3. 현재 정상 동작하는 기능

- Gradle Wrapper를 통한 빌드와 테스트
- Docker Compose PostgreSQL 기동과 영속 volume
- Flyway의 합성 정산 스키마 및 smoke 데이터 초기화
- Flyway 기반 Spring Batch 메타데이터 초기화와 최소 Job 반복 실행
- 작은 입력에서 같은 결과를 두 번 만드는 결정적 seed 함수
- scale seed 실행용 PowerShell/Bash 인터페이스

## 4. 미완료 작업과 측정 대기 항목

- 2단계 OFFSET/Keyset Reader, 공통 Processor/Writer, checksum 및 재시작 정합성
- 3단계 벤치마크 파라미터와 실행시간/건수/checksum/Old Gen 수집
- 4단계 보조 인덱스 ON/OFF와 실제 SQL/EXPLAIN 수집
- 5단계 100k/500k/1m 전체 36회 측정과 결과 집계
- 6단계 아키텍처·방법론·결과 문서와 실제 측정값 기반 블로그 초안
- `results/` 산출물은 아직 생성하지 않음

## 5. 발생한 오류와 확인된 원인

- 첫 Compose 수동 검증에서 호스트 포트 `55433`이 이미 사용 중이어서 bind에 실패했다. 해당 임시 리소스를 정리하고 비어 있던 `65432`를 선택해 재실행했다.
- 첫 DB 확인 SQL에서 `batch_job_execution`에 직접 존재하지 않는 `job_name` 컬럼을 조회해 SQL 오류가 발생했다. 애플리케이션 로그에서 Job `COMPLETED`가 확인됐고, 자동 통합 테스트에서도 `JobExecution`과 Step 상태를 검증했다. 다음 메타데이터 조회는 `batch_job_instance`와 join해야 한다.
- 일반 sandbox에서 Docker 설정 파일과 네트워크 접근이 제한되어, 승인된 로컬 Docker 및 공개 Maven 의존성 명령으로 검증했다. 별도 프로그램 설치나 관리자 권한 작업은 수행하지 않았다.

## 6. 다음 작업에서 바로 시작할 내용

사용자가 `계속 진행해`라고 요청하면 문서와 이 진행 기록을 다시 읽고 2단계만 수행한다.

1. 공통 `SettlementItem` Entity/Projection과 checksum 계약 확정
2. 실제 `JpaPagingItemReader` OFFSET Reader 구현
3. `ExecutionContext.lastId`를 저장하는 Keyset Reader 구현
4. 동일 Processor/Writer, chunk/page size 1000 구성
5. 0/1/999/1000/1001건, ID gap, 혼합 상태, 마지막 페이지, 중간 실패/restart 정합성 테스트
6. 실제 SQL에서 OFFSET 증가와 `id > lastId` 확인

## 7. 실행 및 재현 명령어

```powershell
docker compose up -d --wait postgres
.\gradlew.bat test
.\gradlew.bat bootRun --args="run.id=$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
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
