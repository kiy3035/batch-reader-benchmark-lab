# 프로젝트 진행 상황

마지막 갱신: 2026-09-05 (Asia/Seoul)

## 1. 완료한 작업

- 1단계: Java 21, Spring Boot 3.5.16, Spring Batch 5.2.6, Gradle 8.12.1 프로젝트와 PostgreSQL 16.15 Compose, Flyway 스키마, smoke seed와 결정적 `generate_series` seed 기반 구성
- 1단계: Flyway, DataSource, Batch 메타데이터와 READY 4건을 확인하는 최소 `smokeJob` 실행 검증
- 2단계: 실제 `JpaPagingItemReader` OFFSET Reader와 `id > :lastId` 기반 restartable Keyset Reader 구현
- 2단계: 두 Reader에 같은 Entity, 상태, ID 정렬, chunk/page size 1000, 단일 스레드, Processor/Writer와 checksum 계약 적용
- 2단계: 0건·1000건·1001건·마지막 부분 페이지·ID gap·정렬·누락·중복·실패 후 재시작 정합성 검증
- 3단계: JobParameter 기반 benchmark Job, wall-clock, 처리 건수, checksum, 실패 상태, G1 Old Gen 50ms sampler와 run별 JSON/CSV 저장 구현
- 3단계: 별도 JVM, G1GC, 512MB 고정 heap과 unified GC logging PowerShell/Bash runner 구현
- 4단계: `(status, id)` 보조 인덱스 ON/OFF, `ANALYZE`, 카탈로그 정의 검증과 로컬 DB guard 구현
- 4단계: FIRST/MIDDLE/LAST OFFSET·Keyset SQL, 바인딩과 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` 수집 구현
- 5단계: 환경 수집, 조건별 warm-up 분리, 교차 실행 순서, 중단 재개와 결과 검증을 포함한 전체 matrix 자동화 구현
- 5단계: 100k/500k/1m × OFFSET/Keyset × index OFF/ON × 3회, 공식 36개 run 실행 완료
- 5단계: run별 GC 로그 36개, 조건별 EXPLAIN JSON 36개, raw 36행과 summary 12행 생성
- 5단계: 원본 run JSON에서 요약 통계를 독립 재계산하고 파일 연결, 실행 순서, scale별 checksum 일치를 검증

## 2. 실제 실행한 테스트와 결과

### 자동 테스트

- 공식 측정 전과 결과·문서 갱신 후 `.\gradlew.bat --no-daemon test` 실행: 최종 `BUILD SUCCESSFUL` (1분 33초), 총 20개, 실패 0개, 오류 0개, 건너뜀 0개
- 단위 테스트에서 결정적 seed 공식, Keyset 상태 전이와 빈/부분 페이지, Old Gen sampler, 결과 평균·표준편차·규모 증가 배율·Reader 비율을 검증
- Testcontainers PostgreSQL 16.15에서 두 Reader의 건수/checksum/정렬/SQL, 1001건 재시작, 인덱스 ON/OFF 카탈로그와 EXPLAIN 산출물을 검증

### 공식 전체 측정

- 측정 소스: `26e9f85761583affa1ac963ff1f83dfd316d05af`
- 공식 run 구간: 2026-09-05 05:59:23Z ~ 06:39:44Z
- 36개 모두 `COMPLETED`, `countValid=true`, `indexVerified=true`, 그룹별 성공 run 3개
- 모든 run의 read/write 수가 목표 READY 건수와 일치
- checksum: 100k `202165400000`, 500k `4121634000000`, 1m `16000015000000`
- 평균 실행시간은 `results/summary.csv`에 보존했으며 OFFSET/Keyset 비율은 100k OFF/ON `1.236/1.323`, 500k `4.017/3.104`, 1m `9.082/7.228`
- `results/validation.json`: `valid=true`, measuredRuns 36, groups 12, GC 36, EXPLAIN 36, `summaryRecalculated=true`
- 애플리케이션 로그에서 ERROR/Exception/FAILED가 없고 GC 로그에서 OOM 또는 fatal allocation이 없음을 확인
- 완료 뒤 전용 Compose 컨테이너, network와 volume 정리 완료

### 이전 단계 실행 검증

- Compose PostgreSQL에서 Flyway V1~V4와 `smokeJob` 연속 실행 성공
- 패키징 JAR의 OFFSET/Keyset Job이 smoke READY 4건을 각각 한 번 처리
- READY 3,001건에서 인덱스 OFF/ON, 두 Reader, 3개 위치의 EXPLAIN JSON 12개 생성
- 3,001건 자료는 도구 검증용이며 공식 성능 수치에 포함하지 않음

## 3. 현재 정상 동작하는 기능

- Gradle Wrapper 빌드와 Docker 필요 여부가 구분된 단위·통합 테스트
- Compose PostgreSQL, Flyway 스키마·Batch 메타데이터와 결정적 scale seed
- 공통 조건과 처리 흐름을 사용하는 OFFSET/Keyset Reader, checksum과 재시작 체크포인트
- 파라미터 기반 benchmark 실행, 인덱스 전환·검증, wall-clock과 Old Gen 측정
- run별 독립 JVM과 GC 로그, raw JSON/CSV 및 재계산 가능한 summary CSV 생성
- 실제 SQL·바인딩·EXPLAIN JSON 수집과 공식 결과 연결
- 36-run 고정 matrix, warm-up 분리, 교차 순서, 재개와 완결성 검증

## 4. 미완료 작업과 측정 대기 항목

- 6단계 아키텍처와 데이터 흐름 문서
- 공정한 비교를 위한 통제 변수와 측정 방법 문서
- 공식 결과 표·그래프용 데이터 정리와 EXPLAIN·GC 해석
- 한계와 재현 주의사항, 실제 측정값만 사용한 기술 블로그 초안
- README 재현 절차의 최종 단계 검증

5단계 측정 대기 항목은 없다. 6단계는 사용자가 계속 진행하라고 요청하기 전까지 시작하지 않는다.

## 5. 발생한 오류와 확인된 원인

- 초기 Compose 수동 검증에서 임시 포트 충돌이 있어 사용 가능한 별도 포트로 재실행했다.
- 초기 Batch 메타데이터 조회에서 `job_name` 컬럼 위치를 잘못 가정했다. 이후 `batch_job_instance`와 join하는 조회로 확인했다.
- 재시작 테스트의 최초 실패 플래그가 첫 chunk에서 소비됐다. ID 1001이 포함된 두 번째 chunk에서만 실패하도록 수정했다.
- 웹 starter가 없어 Spring 관리 `ObjectMapper` bean이 없던 EXPLAIN 통합 테스트 오류는 전용 mapper bean 구성으로 해결했다.
- 공식 측정 전 준비 실행에서 쉼표가 포함된 전체 `-Xlog` 문자열을 JobParameter 값으로 전달해 Spring Batch가 타입 구분자로 해석했다. 공식 run이 시작되기 전에 중단했고, run에는 G1GC와 heap 옵션을 기록하며 전체 GC logging 형식은 환경 파일과 실제 로그 경로로 보존하도록 수정했다.
- Windows의 WSL/Bash가 경로 mount와 `/bin/bash` 실행에 실패해 Bash 스크립트의 `bash -n` 검증은 수행하지 못했다. PowerShell 스크립트 parser와 실제 전체 실행은 통과했다.
- sandbox 밖의 로컬 Docker와 공개 Maven 의존성 접근은 승인된 명령으로 수행했다. 별도 프로그램 설치나 관리자 권한 작업은 없었다.

## 6. 다음 작업에서 바로 시작할 내용

사용자가 `계속 진행해`라고 요청하면 `AGENTS.md`, `PROJECT_SPEC.md`, `PROGRESS.md`를 다시 읽고 6단계만 진행한다.

1. `results/` 원본과 summary를 바탕으로 아키텍처·방법론·결과 문서 작성
2. 36개 run과 EXPLAIN/GC 자료를 연결해 결과를 해석하고 그래프용 데이터를 준비
3. 환경 제약, warm-cache 조건, 3회 반복의 한계와 재현 주의사항 기록
4. 실제 결과만 인용하는 `BLOG_DRAFT.md` 작성
5. README 재현 절차를 최종 검증하고 관련 테스트 실행

## 7. 실행 및 재현 명령어

```powershell
# 전체 자동 테스트(Testcontainers 포함)
.\gradlew.bat --no-daemon test

# 공식 전체 matrix를 처음부터 실행
.\scripts\run-full-matrix.ps1

# 중단 뒤 완료된 유효 run을 유지하며 재개
.\scripts\run-full-matrix.ps1 -Resume

# 생성된 결과를 원본 JSON에서 독립 검증
.\scripts\validate-results.ps1
```

단일 조건과 개별 EXPLAIN은 다음처럼 재현한다.

```powershell
.\gradlew.bat bootJar
.\scripts\seed-scale.ps1 -ReadyRows 100000
.\scripts\run-benchmark.ps1 -ReaderType OFFSET -TargetRows 100000 -IndexMode OFF -Repetition 1
.\scripts\collect-explain.ps1 -TargetRows 100000 -IndexMode OFF
```

## 8. 변경한 주요 파일

- 프로젝트/DB: `build.gradle`, Gradle Wrapper, `compose.yaml`, `.env.example`, Flyway V1~V4
- Reader/Batch: `src/main/java/dev/benchmark/batchreader/domain/`, `reader/`, `batch/`, 공통 checksum Processor/Writer
- 측정: `src/main/java/dev/benchmark/batchreader/benchmark/`의 Job, listener, sampler, 결과 모델과 store
- 인덱스/EXPLAIN: `src/main/java/dev/benchmark/batchreader/experiment/`
- 실행 스크립트: `scripts/seed-scale.*`, `run-benchmark.*`, `collect-explain.*`
- 5단계 스크립트: `scripts/run-full-matrix.ps1`, `write-environment.ps1`, `validate-results.ps1`
- 테스트: 단위 테스트와 Testcontainers Reader/인덱스/EXPLAIN 통합 테스트
- 문서: `README.md`, `docs/decisions.md`, `PROGRESS.md`

## 9. 생성된 측정 결과 파일 경로

- 원본 run: `results/runs/*.json` 36개
- 원본 표: `results/raw-runs.csv` 36행
- 요약: `results/summary.csv` 12행
- 환경: `results/environment.json`
- 실행 순서: `results/execution-order.csv` 36행
- 검증: `results/validation.json`
- 실행계획: `results/explain/*.json` 36개
- GC 원본: `results/gc/*.log` 36개
- 비공식 warm-up: `build/stage5-warmup/` (Git 제외)

## 검증 환경

| 항목 | 실제 확인값 |
| --- | --- |
| CPU | 11th Gen Intel Core i5-1135G7 @ 2.40GHz, 8 logical processors |
| Host RAM | 8,379,490,304 bytes |
| OS | Microsoft Windows 11 Home 64-bit, 10.0.26200 build 26200 |
| Java/JVM | Eclipse Temurin OpenJDK 21.0.8+9, HotSpot 64-Bit Server VM |
| Spring Boot / Batch | 3.5.16 / 5.2.6 |
| Hibernate | 6.6.53.Final |
| PostgreSQL | 16.15 Alpine container |
| Docker Engine / Compose | 24.0.7 / 2.23.3-desktop.2 |
| Docker 할당 | 8 vCPU, 3,998,875,648 bytes RAM |
| JVM 측정 조건 | G1GC, `-Xms512m -Xmx512m`, Old Gen 50ms sampling |
