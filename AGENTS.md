# 저장소 작업 규칙

## 1. 프로젝트 목적

이 저장소는 대량 정산 데이터를 Spring Batch로 읽을 때 `LIMIT/OFFSET` 기반 페이징과 Keyset(Zero-Offset) 페이징의 성능 특성을 직접 구현하고, 로컬 환경에서 재현 가능한 수치로 비교하는 개인 기술 블로그 프로젝트다.

- 회사 프로젝트나 실제 운영 성과처럼 표현하지 않는다.
- 스키마와 데이터는 모두 합성 데이터로 구성한다.
- 블로그의 수치와 결론은 실제 실행 결과에서만 가져온다.
- 목표는 특정 Reader가 항상 우월하다고 단정하는 것이 아니라, 데이터 규모·인덱스·조회 방식에 따라 실행시간과 메모리 사용량이 어떻게 달라지는지 보여주는 것이다.

구현을 시작하기 전에 루트의 `PROJECT_SPEC.md`와 `CODEX_PROMPT.md`를 끝까지 읽고 따른다. 지침이 충돌하면 `AGENTS.md`를 우선한다.

## 1.1 로컬·무료 실행 제약

이 프로젝트는 AWS나 유료 클라우드에 배포하지 않는다. 모든 구현과 실험은 로컬 환경에서 무료로 재현할 수 있게 구성한다.

- PostgreSQL은 Docker Compose로 실행한다.
- Java, Spring Boot, Spring Batch, PostgreSQL, Docker, 오픈소스 도구만 사용한다.
- AWS, RDS, EC2, 유료 모니터링, 유료 APM, 종량제 외부 API를 사용하지 않는다.
- 무료 체험, 무료 크레딧 또는 결제수단 등록이 필요한 서비스도 사용하지 않는다.
- 외부 결제 없이 구현·테스트·측정·문서화가 모두 가능해야 한다.
- 운영체제 구성요소나 별도 프로그램 설치, 관리자 권한이 필요한 작업은 임의로 실행하지 말고 필요성과 명령을 사용자에게 먼저 설명한다.
- 실행 결과에는 CPU, RAM, OS, Java/JVM, Spring Boot, Spring Batch, PostgreSQL, Docker 버전을 기록한다.

## 2. 저장소를 다루는 방식

- 기존 코드가 있으면 빌드 도구, 패키지 구조, 코딩 스타일과 이미 완료된 작업을 먼저 파악한다.
- 빈 저장소라면 Java 21, Spring Boot 3.x, Spring Batch 5.x, Gradle, JPA, PostgreSQL을 기본으로 구성한다.
- 사소한 선택은 합리적인 기본값으로 진행하고 근거를 `docs/decisions.md`에 기록한다.
- 사용자가 만든 변경이나 무관한 파일을 덮어쓰거나 되돌리지 않는다.
- 인덱스 생성·삭제, 대량 seed 적재, EXPLAIN ANALYZE는 로컬 Docker 실험 DB에서만 수행한다.
- 비밀값을 코드, 설정, 로그, 결과 파일에 넣지 않는다. `.env.example`에는 예시용 로컬 값만 둔다.
- 실제 측정 전에 자동 테스트로 Reader의 정합성과 측정 도구의 동작을 검증한다.

## 2.1 장기 작업 분할과 체크포인트

이 프로젝트는 최대 100만 건과 반복 실험을 포함하므로 한 번에 전체 작업을 끝내려고 하지 않는다.

- 기본적으로 한 번의 작업에서는 하나의 주요 단계만 완료한다.
- 오래 걸리거나 사용량을 많이 소모할 것으로 예상되면 새 단계를 시작하지 않고 현재 단계를 검증 가능한 상태로 마무리한다.
- 각 단계가 끝나면 관련 테스트를 실행하고 실제 결과를 확인한다.
- 중간에 멈추더라도 컴파일되지 않는 코드나 절반만 변경된 설정을 남기지 않는다.
- 5단계의 전체 측정은 필요하면 `100k → 500k → 1m` 하위 단계로 나눠 실행한다.
- 측정이 예상보다 오래 걸리면 데이터 크기, ChunkSize, 반복 횟수를 몰래 축소하지 않는다. 완료된 케이스까지 저장하고 `PROGRESS.md`에 중단 지점을 기록한 뒤 사용자에게 보고한다.
- 완료하지 않은 작업을 완료했다고 표현하지 않는다.
- 측정하지 않은 수치나 누락된 실행 결과를 추정하거나 만들어내지 않는다.

작업 진행 상황은 루트의 `PROGRESS.md`에 계속 기록한다. 파일이 없으면 생성한다.

`PROGRESS.md`에는 다음 내용을 유지한다.

1. 완료한 작업
2. 실제 실행한 테스트와 결과
3. 현재 정상 동작하는 기능
4. 미완료 작업과 측정 대기 항목
5. 발생한 오류와 확인된 원인
6. 다음 작업에서 바로 시작할 내용
7. 실행 및 재현 명령어
8. 변경한 주요 파일
9. 생성된 측정 결과 파일 경로

각 단계가 끝나면 완료 내용, 테스트 결과, 현재 상태와 다음 단계를 보고하고 멈춘다. 사용자가 `계속 진행해`라고 요청한 뒤에만 다음 주요 단계를 시작한다. 다음 단계를 시작할 때는 `AGENTS.md`, `PROJECT_SPEC.md`, `PROGRESS.md`를 다시 읽고 완료된 작업을 재구현하지 않는다.

권장 작업 단계는 다음과 같다.

### 1단계: 프로젝트와 로컬 DB 기반

- Spring Boot/Spring Batch/Gradle 프로젝트 구성
- PostgreSQL Docker Compose 구성
- Flyway 스키마와 소규모 smoke seed 구성
- 결정적 대량 데이터 생성 스크립트 또는 배치 구성
- 애플리케이션, DB, 기본 Batch Job 실행 확인

### 2단계: 두 Reader 구현과 정합성 검증

- `JpaPagingItemReader` 기반 OFFSET Reader
- `WHERE id > :lastId` 기반 Keyset Reader
- 동일 Processor/Writer와 ChunkSize 1000 적용
- 재시작을 위한 `ExecutionContext`의 `lastId` 저장
- 누락·중복·정렬·재시작 정합성 테스트

### 3단계: 벤치마크와 JVM 측정 도구

- Reader/데이터 크기/인덱스 모드를 JobParameter로 선택
- 실행시간, 처리 건수, checksum, 실패 여부 수집
- 고정 JVM heap과 G1GC 로그 수집
- Old Gen 사용량 샘플링 및 최대값 집계
- raw CSV/JSON과 요약 결과 생성

### 4단계: 인덱스와 EXPLAIN 실험

- `(status, id)` 인덱스 ON/OFF 자동화
- 통계정보 갱신과 인덱스 상태 검증
- 앞·중간·뒤 페이지의 실제 SQL 및 바인딩 값 기록
- `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` 수집
- 두 Reader 쿼리의 실행계획 비교

### 5단계: 전체 측정 실험

- 대상 데이터 10만·50만·100만 건
- OFFSET/Keyset Reader 각각 실행
- ChunkSize와 pageSize 1000 고정
- 인덱스 ON/OFF 각각 3회 측정
- 평균 실행시간, 증가 배율, Reader 간 속도 비율 계산
- Old Gen 최대 사용량과 GC 로그 보존
- 환경 정보와 원본 결과 보존

### 6단계: 문서와 블로그

- 아키텍처와 데이터 흐름 문서
- 공정한 비교를 위한 통제 변수와 측정 방법
- 결과 표와 그래프용 데이터
- EXPLAIN 및 GC 결과 해석
- 한계와 재현 시 주의사항
- 실제 측정값만 사용한 기술 블로그 초안
- README 재현 절차 최종 검증

## 3. 핵심 구현 규칙

### 3.1 공통 조회 조건

두 Reader는 아래 의미가 동일한 데이터만 읽어야 한다.

- 대상 상태: 예를 들어 `status = 'READY'`
- 정렬: `id ASC`
- ChunkSize: 1000
- Reader pageSize/query max results: 1000
- 동일 Entity 또는 동일 Projection
- 동일 Processor와 Writer
- 단일 스레드 Step
- 측정 중 대상 데이터 INSERT/UPDATE/DELETE 금지

OFFSET Reader의 논리 SQL은 다음과 같다.

```sql
SELECT ...
FROM settlement_item
WHERE status = :status
ORDER BY id
LIMIT :pageSize OFFSET :offset;
```

Keyset Reader의 논리 SQL은 다음과 같다.

```sql
SELECT ...
FROM settlement_item
WHERE status = :status
  AND id > :lastId
ORDER BY id
LIMIT :pageSize;
```

JPQL에는 `LIMIT` 문법을 직접 쓰지 않는다. Keyset Reader는 `WHERE e.id > :lastId ORDER BY e.id` JPQL과 JPA의 `setMaxResults(pageSize)`를 사용한다.

### 3.2 OFFSET Reader

- Spring Batch의 실제 `JpaPagingItemReader`를 사용한다.
- `pageSize=1000`, 정렬은 고유한 `id ASC`로 고정한다.
- 공급자가 생성한 SQL이 OFFSET 방식인지 SQL 로그 또는 프록시/테스트로 확인한다.
- 비교를 유리하게 만들기 위해 OFFSET Reader를 고의로 비효율적으로 구현하지 않는다.

### 3.3 Keyset Reader

- 사용자 정의 `ItemStreamReader` 또는 이에 준하는 Spring Batch Reader로 구현한다.
- 마지막으로 정상 반환한 ID를 다음 조회의 `lastId`로 사용한다.
- `lastId`를 `ExecutionContext`에 저장해 재시작 가능한 구조로 만든다.
- ID가 연속적이라고 가정하지 않는다. 삭제나 gap이 있어도 다음 행을 정상적으로 읽어야 한다.
- 정렬 키는 유일하고 불변이어야 한다.
- 한 페이지를 메모리에 담은 뒤 항목을 하나씩 반환할 수 있지만 전체 결과를 한꺼번에 적재하면 안 된다.
- 매 페이지 뒤 영속성 컨텍스트가 불필요하게 커지지 않도록 관리한다.

## 4. 데이터와 정합성 규칙

- 정산성 합성 테이블은 최소한 `id`, `status`, `customer_id`, `amount`, `settled_at`, payload성 컬럼을 가진다.
- `id`는 PK로 유지한다. PK 인덱스를 제거해 비현실적인 실험을 만들지 않는다.
- 인덱스 ON/OFF 실험은 공통 조건과 정렬을 지원하는 `(status, id)` 보조 인덱스를 대상으로 한다.
- 각 규모에서 실제 Reader가 처리한 `READY` 행 수가 정확히 100,000 / 500,000 / 1,000,000건이 되도록 seed를 구성한다.
- 대량 seed는 결정적이어야 하며 같은 입력으로 동일한 행 수와 값을 만든다.
- Java에서 100만 개 Entity를 만들어 한 번에 `saveAll`하지 않는다. PostgreSQL `generate_series`, SQL seed, COPY 또는 bounded batch insert를 사용한다.
- 각 실행은 처리 건수뿐 아니라 안정적인 checksum을 비교해 두 Reader가 동일한 ID 집합을 한 번씩 읽었음을 확인한다.
- 작은 테스트 데이터에는 ID gap, 마지막 페이지가 1000 미만인 경우, 0건, 정확히 1000건, 1001건을 포함한다.

## 5. 인덱스와 실행계획 규칙

- PostgreSQL PK는 자동으로 인덱스를 가지므로 `id PK 인덱스 OFF`라고 잘못 표현하지 않는다.
- 실험 인덱스 이름을 고정하고 존재 여부를 시스템 카탈로그로 검증한다.
- 인덱스 생성/삭제 후 필요한 경우 `ANALYZE settlement_item`을 수행한다.
- Index OFF는 지정한 보조 인덱스가 실제로 없는 상태여야 한다.
- Index ON은 동일한 정의의 인덱스가 실제로 존재하는 상태여야 한다.
- EXPLAIN은 측정에 사용한 실제 WHERE 조건, OFFSET 또는 lastId 값과 동일한 SQL로 수행한다.
- 최소한 첫 페이지와 후반부 대표 페이지를 수집한다. 후반부 OFFSET과 그에 대응하는 lastId를 함께 기록한다.
- `EXPLAIN ANALYZE` 자체의 오버헤드는 Step 실행시간 결과와 분리한다.
- 실행계획에서 Scan 유형, 실제/예상 rows, loops, rows removed, shared hit/read, sort, planning/execution time을 추출한다.

## 6. 성능 측정 규칙

### 6.1 실험 매트릭스

기본 전체 매트릭스는 다음과 같다.

- 처리 건수: 100k, 500k, 1m
- Reader: OFFSET, KEYSET
- 인덱스: OFF, ON
- 반복: 각 조합 3회

총 36개의 측정 run을 원칙으로 한다. 단, 동일 조건의 준비용 warm-up은 평균 계산에서 제외한다.

### 6.2 통제 변수

- 동일한 로컬 장비와 Docker 자원 설정
- 동일한 데이터 snapshot
- 동일한 Java/JVM 버전
- 동일한 `-Xms`, `-Xmx`, GC 알고리즘
- 동일한 ChunkSize/pageSize 1000
- 동일 Processor/Writer
- 단일 스레드
- 동일 로그 수준
- 측정 run마다 별도 JobInstance 식별자
- 디버거, IDE 프로파일러 등 추가 오버헤드 제거

DB/OS 캐시를 완전히 초기화했다고 근거 없이 주장하지 않는다. 관리자 권한 없이 OS page cache를 강제로 비우지 않는다. 기본 결과는 명시적인 warm-up 뒤의 warm-cache 비교로 정의하고, Reader 실행 순서에 따른 편향을 줄이기 위해 반복 순서를 교차하거나 실행 순서를 결과에 기록한다.

### 6.3 실행시간과 배율

- Step 시작부터 종료까지 wall-clock 시간을 나노초 단위로 측정하고 결과에는 밀리초와 초를 함께 기록한다.
- 각 조합의 3회 원본값과 산술평균을 모두 보존한다.
- 증가 배율은 최소한 `500k 평균 / 100k 평균`, `1m 평균 / 100k 평균`, `1m 평균 / 500k 평균`을 계산한다.
- Reader 간 비교는 동일 데이터 크기·동일 인덱스 모드에서 `OFFSET 평균 / KEYSET 평균`으로 계산한다.
- 평균만 남기지 말고 최소/최대와 표준편차도 함께 계산할 수 있게 raw 결과를 보존한다.

### 6.4 JVM과 Old Gen

- Java 21의 G1GC를 명시적으로 사용하고 heap 크기를 고정한다.
- 각 측정 run을 가능하면 별도 JVM 프로세스로 실행해 이전 run의 heap 상태 영향을 줄인다.
- Unified GC Logging으로 run별 로그 파일을 생성한다.
- 예시 JVM 옵션은 `-XX:+UseG1GC -Xms512m -Xmx512m -Xlog:gc*,safepoint:file=<run-file>:time,uptime,level,tags`이며 실제 값은 설정과 결과에 기록한다.
- Old Gen 최대 사용량은 `MemoryPoolMXBean`의 `G1 Old Gen` 사용량을 고정 간격으로 샘플링해 run별 peak bytes로 기록한다.
- GC 로그는 GC 횟수, pause, promotion/old-region 변화를 검증하기 위한 원본 근거로 보존한다.
- `MemoryPoolMXBean` 측정값과 GC 로그에서 직접 확인한 값을 혼동하지 않고 결과 문서에 측정 방법을 명시한다.

## 7. 테스트 원칙

- 단위 테스트: Keyset 상태 전이, lastId 갱신, 마지막 페이지, 빈 결과, ID gap.
- 통합 테스트: Testcontainers PostgreSQL에서 두 Reader의 동일 건수/checksum 검증.
- 재시작 테스트: 의도적 실패 후 동일 JobInstance 재시작 시 누락·중복 없이 완료되는지 검증.
- SQL 검증: OFFSET Reader가 후반 페이지에서 OFFSET을 증가시키고 Keyset Reader가 `id > lastId`를 사용하는지 확인.
- 인덱스 자동화 테스트: ON/OFF 후 실제 카탈로그 상태 확인.
- 결과 집계 테스트: 평균과 증가 배율 계산 검증.
- 대량 100만 건 전체 실험을 일반 `test` 태스크에 포함하지 않는다. smoke/integration/benchmark 태스크를 구분한다.
- Docker가 필요한 테스트와 필요 없는 테스트를 분리하고 README에 명령을 기록한다.

## 8. 결과물 규칙

최종 결과물에는 최소한 다음을 포함한다.

- 실행 가능한 Spring Batch 애플리케이션
- PostgreSQL Docker Compose와 Flyway migration
- 결정적 seed 도구
- Reader 정합성 자동 테스트
- 벤치마크 실행 스크립트
- `results/raw-runs.csv`
- `results/summary.csv`
- `results/environment.json`
- `results/explain/*.json`
- `results/gc/*.log`
- `docs/architecture.md`
- `docs/methodology.md`
- `docs/result-report.md`
- `BLOG_DRAFT.md`
- 단계별 상태를 기록한 `PROGRESS.md`

Windows PowerShell에서 실행 가능한 스크립트를 우선 제공하고, 가능하면 Bash 대안도 함께 제공한다. 스크립트의 한글 출력이 Windows 콘솔에서 깨지지 않도록 UTF-8 처리 또는 영문 로그를 사용한다.

## 9. 완료 기준

다음 조건을 모두 충족해야 프로젝트 완료로 본다.

- 두 Reader가 동일한 대상 행을 누락·중복 없이 처리한다.
- ChunkSize와 pageSize 1000이 실제 실행에 적용된다.
- 100k/500k/1m, Reader 2종, Index ON/OFF, 3회 반복 결과가 모두 존재한다.
- 실패하거나 중단된 run은 성공 결과처럼 평균에 포함하지 않는다.
- EXPLAIN JSON과 GC 로그가 각 실험 조건에 연결된다.
- Old Gen peak 측정 방식과 단위가 명확하다.
- 결과 수치는 자동 생성한 raw 데이터로 재계산할 수 있다.
- 환경 정보와 실험 한계를 함께 공개한다.
- 새 로컬 환경에서 README 절차를 따라 재현할 수 있다.

