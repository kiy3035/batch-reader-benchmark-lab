# Batch Reader Benchmark Lab 프로젝트 명세

## 1. 한 줄 정의

Spring Batch의 `JpaPagingItemReader` 기반 LIMIT/OFFSET 페이징과 `WHERE id > lastId` 기반 Keyset 페이징을 동일 조건에서 비교하고, 데이터 증가·인덱스·JVM Old Gen에 미치는 영향을 로컬에서 측정하는 프로젝트다.

## 2. 문제 정의

LIMIT/OFFSET 쿼리는 뒤 페이지로 이동할수록 OFFSET 앞부분의 행을 찾아 건너뛰는 비용이 누적될 수 있다. Spring Batch로 대량 정산 데이터를 순차 처리할 때 이 비용이 전체 Step 실행시간 증가로 이어지는지 검증한다.

비교 대상은 다음 두 방식이다.

1. Spring Batch `JpaPagingItemReader`
   - JPQL + `setFirstResult` + `setMaxResults`
   - PostgreSQL에서는 일반적으로 LIMIT/OFFSET SQL로 변환
2. Keyset Reader
   - `WHERE id > :lastId ORDER BY id`
   - JPA `setMaxResults(pageSize)`로 다음 1000건 조회
   - 마지막 ID를 ExecutionContext에 저장

단순 실행시간만 비교하지 않고 인덱스 ON/OFF의 실행계획, JVM GC 로그, Old Gen peak를 함께 수집한다.

## 3. 범위

### 포함

- Java 21 / Spring Boot 3.x / Spring Batch 5.x / Gradle
- Spring Data JPA 또는 JPA EntityManager
- PostgreSQL Docker Compose
- Flyway migration
- 결정적 합성 정산 데이터 100k/500k/1m
- OFFSET Reader와 Keyset Reader
- ChunkSize/pageSize 1000
- 인덱스 ON/OFF
- 각 실험 3회 반복
- EXPLAIN ANALYZE BUFFERS JSON
- GC unified log
- Old Gen peak sampler
- CSV/JSON 결과 및 블로그 초안

### 제외

- AWS 및 클라우드 배포
- 유료 APM/모니터링
- Kafka, Redis, 메시지 브로커
- 멀티스레드 Step, partitioning, remote chunking
- 실제 개인정보 또는 회사 데이터
- 두 Reader 외의 추가 최적화 Reader 비교
- 처리 중 데이터 변경이 있는 온라인 시나리오

## 4. 권장 폴더명

```text
batch-reader-benchmark-lab
```

권장 GitHub Description:

```text
Local Spring Batch benchmark comparing OFFSET and keyset readers across data scale, index plans, and JVM Old Gen usage.
```

## 5. 기술 구성

- Java 21
- Spring Boot 3.x
- Spring Batch 5.x
- Spring Data JPA / Hibernate
- PostgreSQL 16+
- Flyway
- Gradle Wrapper
- JUnit 5
- Testcontainers PostgreSQL
- Docker Compose
- PowerShell benchmark scripts

정확한 패치 버전은 구현 시점의 호환 가능한 안정 버전으로 고정하고 README와 환경 결과에 기록한다. 동적 버전이나 `latest` 태그는 사용하지 않는다.

## 6. 데이터 모델

### settlement_item

| 컬럼 | 타입 | 설명 |
| --- | --- | --- |
| id | BIGINT PK | 고유하고 증가하는 정렬/커서 키 |
| status | VARCHAR(20) | READY, COMPLETED 등 처리 조건 |
| customer_id | BIGINT | 합성 고객 식별자 |
| amount | NUMERIC(15,2) | 합성 정산 금액 |
| settled_at | TIMESTAMP | 합성 정산 시각 |
| reference_no | VARCHAR(40) | 합성 업무 참조번호 |
| payload | VARCHAR 또는 JSONB | 행 크기를 현실화하는 합성 데이터 |

기본 실험 대상은 `status = 'READY'`다. 각 scale에서 READY 대상 행 수가 정확히 100k/500k/1m이 되도록 한다. 비교 대상 밖의 상태 행을 일부 포함할 수 있지만 그 수와 비율은 모든 Reader에서 동일하게 유지하고 결과에 기록한다.

### 실험 인덱스

```sql
CREATE INDEX idx_settlement_item_status_id
    ON settlement_item (status, id);
```

`id`는 PK이므로 PostgreSQL에 PK 인덱스가 항상 존재한다. 실험에서 ON/OFF하는 것은 조건과 정렬을 함께 지원하는 위 보조 인덱스다.

## 7. Reader 상세

### 7.1 OffsetJpaPagingReader

- 실제 `JpaPagingItemReader<SettlementItem>` 사용
- Query: `SELECT s FROM SettlementItem s WHERE s.status = :status ORDER BY s.id ASC`
- `pageSize=1000`
- Step chunk size 1000
- reader name과 saveState를 명시

후반 페이지에서 Hibernate가 생성한 SQL 또는 동등한 실제 SQL의 OFFSET 값을 확인한다.

### 7.2 KeysetJpaItemReader

- `ItemStreamReader<SettlementItem>` 구현
- 최초 `lastId=0` 또는 null 경계값
- Query: `SELECT s FROM SettlementItem s WHERE s.status = :status AND s.id > :lastId ORDER BY s.id ASC`
- `setMaxResults(1000)`
- 페이지 내 Entity를 순차 반환
- 페이지 소진 후 마지막 Entity의 id를 다음 조회 기준으로 사용
- `ExecutionContext` key에 lastId 저장
- restart 시 저장된 lastId 다음 행부터 재개
- page 경계에서 EntityManager clear 또는 동등한 방식으로 persistence context 크기 제한

JPQL 문자열에 `LIMIT`을 넣지 않는다.

## 8. Processor와 Writer

두 Reader가 읽기 성능 비교의 중심이 되도록 공통 Processor/Writer를 사용한다.

- Processor: ID와 amount를 이용한 가벼운 deterministic checksum 계산
- Writer: 외부 DB 쓰기 없이 처리 건수와 checksum 집계
- 동일 로직을 두 Step에서 공유
- 전체 Entity나 전체 ID 목록을 메모리에 쌓지 않음
- Step 종료 후 count/checksum을 결과에 기록

두 Reader의 처리 건수와 checksum이 다르면 해당 run은 성능 비교에서 제외하고 실패로 기록한다.

## 9. Batch Job과 파라미터

권장 JobParameter:

| 파라미터 | 예 | 의미 |
| --- | --- | --- |
| readerType | OFFSET / KEYSET | Reader 선택 |
| targetRows | 100000 / 500000 / 1000000 | 처리 대상 규모 |
| indexMode | ON / OFF | 보조 인덱스 상태 |
| repetition | 1 / 2 / 3 | 반복 번호 |
| runId | UUID 또는 timestamp | JobInstance 구분 |
| status | READY | 공통 조회 조건 |

scale별 데이터 준비 방식은 다음 중 하나를 선택해 `docs/decisions.md`에 근거를 남긴다.

1. 규모별 DB를 재구성하고 정확한 대상 행을 적재
2. 최대 데이터셋에 scale 구분 조건을 추가해 정확한 대상 행 집합 선택

어느 방식을 선택하든 Reader마다 물리적으로 같은 행 집합을 읽고, 인덱스 정의가 공정해야 한다.

## 10. 정합성 시나리오

자동 테스트 데이터는 최소한 다음을 포함한다.

- 대상 0건
- 대상 1건
- 대상 999건
- 대상 1000건
- 대상 1001건
- 여러 페이지
- ID gap 존재
- READY 이외 상태 혼재
- 마지막 chunk 미만 페이지
- 중간 실패 후 restart

검증 항목:

- OFFSET/KEYSET count 동일
- OFFSET/KEYSET checksum 동일
- ID 중복 없음
- ID 누락 없음
- 오름차순 처리
- restart 뒤 전체 결과가 1회 처리와 동일

## 11. 벤치마크 실행 설계

### 전체 매트릭스

| 대상 행 | Reader | Index | 반복 |
| ---: | --- | --- | ---: |
| 100,000 | OFFSET, KEYSET | OFF, ON | 각 3회 |
| 500,000 | OFFSET, KEYSET | OFF, ON | 각 3회 |
| 1,000,000 | OFFSET, KEYSET | OFF, ON | 각 3회 |

총 측정 run은 36개다. 각 조건은 측정 전 warm-up을 수행할 수 있으나 warm-up은 평균에서 제외한다.

### 실행 순서

첫 방식이 DB cache 이점을 독점하지 않도록 Reader 순서를 교차한다.

- 반복 1: OFFSET → KEYSET
- 반복 2: KEYSET → OFFSET
- 반복 3: OFFSET → KEYSET

Index OFF/ON 순서와 데이터 재적재 순서도 raw 결과에 남긴다. 완전한 cold cache를 보장하지 않으며 결과 문서에 warm-cache 로컬 비교임을 명시한다.

### 프로세스 격리

가능하면 각 측정 run은 별도 JVM 프로세스로 실행한다. 공통 JVM 옵션 예시는 다음과 같다.

```text
-XX:+UseG1GC
-Xms512m
-Xmx512m
-Xlog:gc*,safepoint:file=results/gc/<run-id>.log:time,uptime,level,tags
```

실제 적용 옵션은 `environment.json`과 raw run에 저장한다.

## 12. 수집 지표

### run별 지표

- runId
- startedAt / endedAt
- readerType
- targetRows
- indexMode
- repetition
- durationNs / durationMs
- readCount / writeCount / commitCount
- checksum
- exitStatus
- peakOldGenBytes
- gcLogPath
- explainArtifactPath
- JVM 옵션
- 실행 순서

### 요약 지표

- 3회 실행시간 원본
- 평균, 최소, 최대, 표준편차
- 500k / 100k 증가 배율
- 1m / 100k 증가 배율
- 1m / 500k 증가 배율
- 동일 scale/index에서 OFFSET / KEYSET 시간 비율
- 각 조합 Old Gen peak 평균/최대

## 13. Old Gen 측정

`MemoryPoolMXBean`에서 이름이 `G1 Old Gen`인 pool을 찾고, Step 실행 중 고정 주기로 `getUsage().getUsed()`를 샘플링한다. 최대값을 `peakOldGenBytes`로 기록한다.

- 샘플링 간격은 설정 가능하게 하고 기본값을 문서화한다.
- sampler 자체 오버헤드를 모든 Reader에 동일하게 적용한다.
- 사용한 GC가 G1이 아니거나 Old Gen pool을 찾지 못하면 0으로 조용히 기록하지 않고 해당 run을 측정 실패 또는 N/A로 기록한다.
- GC 로그는 별도 원본 파일로 남기고 pause와 old-region 변화를 해석하는 근거로 사용한다.

## 14. EXPLAIN 설계

다음 SQL에 대해 Index OFF/ON 결과를 각각 JSON으로 저장한다.

1. OFFSET 첫 페이지
2. OFFSET 후반부 대표 페이지
3. KEYSET 첫 페이지
4. KEYSET 후반부 대응 lastId

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT id, status, customer_id, amount, settled_at, reference_no, payload
FROM settlement_item
WHERE status = 'READY'
ORDER BY id
LIMIT 1000 OFFSET :offset;
```

```sql
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT id, status, customer_id, amount, settled_at, reference_no, payload
FROM settlement_item
WHERE status = 'READY'
  AND id > :lastId
ORDER BY id
LIMIT 1000;
```

후반부 OFFSET과 lastId는 같은 위치 다음의 1000건을 나타내도록 결정하고 값을 메타데이터에 함께 저장한다.

## 15. 결과 파일 규격

```text
results/
  raw-runs.csv
  summary.csv
  environment.json
  explain/
    <scale>-<reader>-<index>-<position>.json
  gc/
    <run-id>.log
  runs/
    <run-id>.json
```

결과 파일은 스크립트로 다시 생성할 수 있어야 한다. 큰 generated seed나 DB volume은 Git에 커밋하지 않는다. 실제 측정 결과는 크기를 확인한 뒤 텍스트 기반 CSV/JSON/요약 로그만 커밋한다.

## 16. API와 실행 방식

이 프로젝트의 핵심은 Batch Job과 실험 스크립트다. 불필요한 웹 UI는 만들지 않는다.

필수 실행 진입점:

- DB 시작/중지
- smoke seed 적재
- scale별 seed 준비
- 정합성 테스트
- 단일 benchmark run
- 전체 benchmark matrix
- 결과 요약 생성
- EXPLAIN 수집

Windows PowerShell에서 한글 경로와 인코딩 문제 없이 실행 가능하게 한다.

## 17. 예상 디렉터리 구조

```text
batch-reader-benchmark-lab/
  AGENTS.md
  PROJECT_SPEC.md
  CODEX_PROMPT.md
  PROGRESS.md
  README.md
  build.gradle
  settings.gradle
  compose.yaml
  src/main/java/.../batch/
  src/main/java/.../reader/
  src/main/java/.../benchmark/
  src/main/resources/db/migration/
  src/test/java/.../
  scripts/
  docs/
  results/
```

## 18. 완료 후 블로그가 답해야 할 질문

1. JpaPagingItemReader가 뒤 페이지에서 왜 느려질 수 있는가?
2. Keyset Reader는 앞 행을 건너뛰는 비용을 어떻게 제거하는가?
3. 100k, 500k, 1m에서 실행시간은 각각 어떻게 증가했는가?
4. 인덱스 ON/OFF가 두 방식에 각각 어떤 영향을 주었는가?
5. EXPLAIN의 Scan, rows, loops, buffers에서 차이가 어떻게 보였는가?
6. Old Gen peak와 GC pause는 실제로 차이가 있었는가?
7. Keyset 방식의 전제와 한계는 무엇인가?
8. 재현 환경과 캐시 조건은 무엇이었는가?

결과가 예상과 다르더라도 실제 결과를 그대로 기록한다. 차이가 작거나 OFFSET이 특정 조건에서 빠르게 나오더라도 수치를 수정하거나 선택적으로 누락하지 않는다.

