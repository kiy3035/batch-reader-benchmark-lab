# Codex 구현 요청서

## 역할

너는 Java 21, Spring Boot, Spring Batch, JPA, PostgreSQL 성능 실험을 구현하고 검증하는 시니어 백엔드 엔지니어다.

이 저장소에서 `JpaPagingItemReader`의 LIMIT/OFFSET 방식과 `WHERE id > lastId` Keyset 방식의 실행시간·증가 배율·실행계획·Old Gen 사용량을 비교하는 재현 가능한 로컬 프로젝트를 구현한다.

## 필수 사전 확인

작업 전에 루트의 다음 문서를 모두 끝까지 읽는다.

1. `AGENTS.md`
2. `PROJECT_SPEC.md`
3. `PROGRESS.md`가 있으면 해당 문서

규칙이 충돌하면 `AGENTS.md`를 우선한다. 이미 완료된 작업을 다시 만들거나 되돌리지 않는다.

## 핵심 요구사항

### 비교 Reader

1. OFFSET 방식
   - Spring Batch `JpaPagingItemReader` 사용
   - `status = READY`, `ORDER BY id ASC`
   - pageSize 1000
2. Keyset 방식
   - JPQL `WHERE status = :status AND id > :lastId ORDER BY id ASC`
   - JPA `setMaxResults(1000)` 사용
   - lastId를 ExecutionContext에 저장하는 restartable Reader

두 Reader는 동일한 Entity, 조건, 정렬, Processor, Writer, ChunkSize 1000을 사용한다.

### 실험 범위

- READY 대상 100,000 / 500,000 / 1,000,000건
- Reader OFFSET / KEYSET
- 보조 인덱스 `(status, id)` OFF / ON
- 각 조합 3회 반복
- Step 실행시간 원본과 평균
- 데이터 증가 배율
- 동일 조건 Reader 간 시간 비율
- EXPLAIN ANALYZE BUFFERS JSON
- Unified GC log
- G1 Old Gen peak 사용량

### 정합성

- count와 checksum으로 두 Reader가 같은 행을 정확히 한 번씩 읽었는지 검증한다.
- ID gap, 마지막 부분 페이지, 빈 결과, restart를 자동 테스트한다.
- 정합성이 깨진 run은 성능 평균에 포함하지 않는다.

### 환경

- Java 21 / Spring Boot 3.x / Spring Batch 5.x / Gradle
- PostgreSQL Docker Compose
- Flyway와 결정적 합성 seed
- Testcontainers 기반 통합 테스트
- Windows PowerShell 재현 스크립트
- 모든 작업은 로컬에서 무료로 실행
- AWS, 유료 API, 유료 SaaS, 결제수단 등록 서비스 사용 금지

## 구현 시 주의사항

- JPQL에 `LIMIT`을 직접 작성하지 말고 `setMaxResults`를 사용한다.
- `id` PK 인덱스를 제거하지 않는다. 인덱스 실험은 `(status, id)` 보조 인덱스로 한다.
- OFFSET Reader를 고의로 불리하게 만들지 않는다.
- Keyset Reader가 전체 결과를 메모리에 쌓지 않게 한다.
- 두 Reader 모두 단일 스레드로 실행한다.
- Processor/Writer의 비용이 Reader 차이를 덮지 않도록 가볍고 동일하게 구성한다.
- 대량 seed를 JPA `saveAll`로 한꺼번에 만들지 않는다.
- OS cache를 지웠다고 근거 없이 주장하지 않는다.
- warm-up은 측정 평균에서 제외하고 실행 순서를 raw 결과에 기록한다.
- 실제 측정값을 만들거나 보정하지 않는다.
- 100만 건 OFFSET 실험이 오래 걸리더라도 규모나 반복 횟수를 임의로 줄이지 않는다.

## 작업 단계

전체 작업을 한 번에 하지 말고 `AGENTS.md`의 1~6단계를 따른다. 한 단계가 끝날 때마다 테스트하고 `PROGRESS.md`를 갱신한 후 멈춘다.

### 이번 최초 작업: 1단계만 수행

이번 작업에서는 다음만 구현한다.

- Spring Boot/Spring Batch/Gradle 프로젝트 초기 구성
- PostgreSQL Docker Compose
- Flyway 기본 스키마
- 소규모 smoke seed
- 결정적 대량 데이터 생성 방식의 기반
- 최소 Batch Job과 애플리케이션 기동 확인
- 관련 단위/통합 테스트
- README의 1단계 실행 방법
- `docs/decisions.md`
- `PROGRESS.md`

이번 작업에서는 다음을 하지 않는다.

- 100k/500k/1m 전체 실험 실행
- 두 Reader의 최종 성능 비교
- 인덱스 ON/OFF 전체 EXPLAIN 수집
- GC/Old Gen 전체 측정
- 결과 수치 작성
- 블로그 초안 작성
- 2단계 이후 작업 선행

## 검증과 보고

계획만 작성하고 멈추지 말고 1단계 범위의 구현과 검증까지 수행한다.

운영체제 구성요소나 별도 프로그램 설치, 관리자 권한이 필요하면 임의로 실행하지 말고 이유와 사용자가 실행할 명령을 먼저 설명한다.

완료 후 `PROGRESS.md`에 다음을 기록한다.

1. 완료 내용
2. 실행한 테스트와 실제 결과
3. 현재 정상 동작하는 기능
4. 미완료 작업
5. 발생한 오류와 확인된 원인
6. 다음 단계
7. 재현 명령어
8. 변경한 주요 파일

마지막 응답에는 완료 내용, 테스트 결과, 현재 상태와 2단계에서 진행할 내용을 요약하고 멈춘다. 사용자가 계속 진행하라고 하기 전까지 2단계를 시작하지 않는다.

