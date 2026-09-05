# 실험 방법

## 질문

이 실험은 같은 READY 행을 ID 오름차순으로 전부 처리할 때 다음 항목이 어떻게 달라지는지 확인한다.

1. 데이터가 100k에서 500k, 1m으로 늘 때 OFFSET과 Keyset의 Step 실행시간은 어떻게 증가하는가?
2. `(status, id)` 보조 인덱스 ON/OFF가 실행시간과 실제 실행계획에 어떤 차이를 만드는가?
3. 두 Reader가 같은 행을 한 번씩 처리했는가?
4. 고정 512MiB heap에서 Step 구간 Old Gen peak와 JVM 전체 GC 로그는 어떤 모습을 보이는가?

## 환경

환경은 측정 직전에 `results/environment.json`에 수집했다.

| 항목 | 공식 측정값 |
| --- | --- |
| OS | Windows 11 Home 64-bit, 10.0.26200 build 26200 |
| CPU | Intel Core i5-1135G7, logical processor 8개 |
| Host RAM | 8,379,490,304 bytes |
| Java | Eclipse Temurin 21.0.8+9, HotSpot 64-Bit Server VM |
| Spring Boot / Batch | 3.5.16 / 5.2.6 |
| Hibernate | 6.6.53.Final |
| PostgreSQL | 16.15 Alpine container |
| Docker Engine / Compose | 24.0.7 / 2.23.3-desktop.2 |
| Docker 할당 | 8 vCPU, 3,998,875,648 bytes RAM |

공식 JAR는 깨끗한 측정 소스 커밋 `26e9f85761583affa1ac963ff1f83dfd316d05af`에서 한 번 빌드했다. 모든 run이 같은 JAR를 사용했다.

## 데이터 생성과 snapshot

`prepare_settlement_seed(targetRows)`는 테이블을 비우고 PostgreSQL `generate_series`로 데이터를 다시 만든다. READY 건수는 정확히 100,000, 500,000, 1,000,000이고 COMPLETED는 READY의 10%다. 고객, 금액, 시각, 참조번호와 payload는 ID로 계산하므로 같은 입력은 같은 행을 만든다.

각 scale에서 seed를 한 번 준비한 뒤 4개 조건의 warm-up과 12개 공식 run이 같은 행 snapshot을 읽었다. Reader 실행 중에는 INSERT, UPDATE, DELETE를 하지 않았다. 인덱스 모드 전환만 조건에 따라 수행했다.

READY가 앞 ID 구간에 몰리고 COMPLETED가 뒤에 이어지는 분포는 실제 업무의 상태 분포를 대표하지 않는다. 특히 상태 선택도가 높은 경우의 보조 인덱스 효과를 평가하기에는 제한이 있다.

## 실험 matrix와 실행 순서

공식 matrix는 3 scale × 2 Reader × 2 index mode × 3회로 총 36개다. pageSize와 chunkSize는 모두 1000이다.

| 반복 | 인덱스 순서 | 각 인덱스 안의 Reader 순서 |
| ---: | --- | --- |
| 1 | OFF → ON | OFFSET → KEYSET |
| 2 | ON → OFF | KEYSET → OFFSET |
| 3 | OFF → ON | OFFSET → KEYSET |

각 scale의 Reader/index 조합은 공식 측정 전에 한 번 warm-up했다. warm-up 결과는 `build/stage5-warmup/`에 분리했고 평균에서 제외했다. OS page cache는 관리자 권한으로 비우지 않았다. 따라서 결과는 실행 순서가 기록된 로컬 warm-cache 비교다.

## 통제 변수

- 상태 `READY`, 정렬 `id ASC`
- 같은 Entity와 조회 컬럼
- pageSize/chunkSize 1000
- 같은 checksum Processor와 집계 Writer
- 단일 스레드 Step
- 같은 데이터 snapshot
- run마다 별도 JobInstance와 JVM 프로세스
- G1GC, `-Xms512m -Xmx512m`
- 50ms Old Gen sampling
- 같은 애플리케이션 로그 수준
- 디버거와 외부 profiler 미사용

Processor는 `id * 31 + amount의 센트 값`을 계산하고 Writer는 count와 checksum만 누적한다. 외부 쓰기나 전체 ID 목록 보관은 하지 않는다.

## 실행시간과 집계

Step listener가 시작 직전에 `System.nanoTime()`을 기록하고 Step 종료 직후 차이를 계산한다. seed, 애플리케이션 부팅, 인덱스 DDL, `ANALYZE`, 카탈로그 검증과 EXPLAIN 시간은 Step 실행시간에서 제외된다.

각 조건의 성공 run 3개로 다음 값을 계산한다.

- 산술평균, 최소, 최대
- 모표준편차: 세 run을 해당 조건의 전체 관측값으로 취급
- `500k 평균 / 100k 평균`
- `1m 평균 / 100k 평균`
- `1m 평균 / 500k 평균`
- 같은 scale/index에서 `OFFSET 평균 / KEYSET 평균`

`results/runs/*.json`이 run별 기준 원본이고 `raw-runs.csv`는 이를 표 형태로 펼친다. `summary.csv`는 차트와 비교표에 쓰기 위한 12개 조건 집계다. 평균만 남기지 않기 위해 원본, 최소·최대와 표준편차를 모두 보존한다. `validate-results.ps1`은 성공·건수·인덱스·파일 연결을 확인하고 JSON에서 summary의 모든 통계를 독립 재계산한다.

## 정합성과 유효 run

공식 run은 다음 조건을 모두 만족할 때만 summary에 들어간다.

- `exitStatus=COMPLETED`
- `readCount=writeCount=targetRows`
- `countValid=true`
- 요청한 보조 인덱스 상태가 카탈로그 검증과 일치

동일 scale의 모든 Reader/index/repetition checksum도 같아야 한다. 공식 checksum은 100k `202165400000`, 500k `4121634000000`, 1m `16000015000000`이었다. 자동 테스트는 빈 입력, 1/999/1000/1001건, 마지막 부분 페이지, ID gap, 다른 상태 혼재, 정렬과 재시작을 별도로 다룬다.

## 인덱스와 EXPLAIN

실험에서 끄고 켜는 인덱스는 `idx_settlement_item_status_id (status, id)`다. PK와 `settlement_item_pkey`는 항상 유지한다. ON/OFF 변경 뒤 `ANALYZE settlement_item`을 실행하고 시스템 카탈로그에서 실제 상태를 확인한다.

EXPLAIN은 각 scale과 index mode에서 두 Reader의 FIRST, MIDDLE, LAST 페이지를 수집한다. OFFSET의 `pageOffset` 직전 실제 ID를 Keyset의 `lastId`로 사용해 같은 다음 페이지를 비교한다. 원본은 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`이며 Batch Step 시간과 별도로 저장한다.

`results/report/explain.csv`의 `scanActualRows`와 `examinedPerReturnedRow`는 선택된 `settlement_item` scan node의 실제 행 수를 나타낸다. 모든 공식 plan이 `settlement_item_pkey`를 선택했으므로 이번 데이터에서 보조 인덱스 ON/OFF는 plan 종류를 바꾸지 않았다.

## Old Gen과 GC

서로 다른 범위의 두 지표를 구분한다.

- `peakOldGenBytes`: Step 실행 중 50ms마다 `G1 Old Gen` MXBean을 읽은 최대값
- `results/gc/*.log`: 애플리케이션 부팅부터 종료까지 JVM 프로세스 전체의 unified GC 로그

`build-report-data.ps1`은 GC 로그의 완료된 pause 행, Young Normal pause, Full GC와 `Old regions`를 파싱한다. 이 수치는 JVM 전체 범위이므로 Step 전용 pause 시간으로 표현하지 않는다. Step 실행시간과 직접 연결할 때도 부팅·종료 활동이 포함됐다는 제한을 함께 밝힌다.

## 보고서용 파생 데이터

다음 명령은 공식 원본을 수정하지 않고 보고서용 CSV를 다시 만든다.

```powershell
.\scripts\build-report-data.ps1
```

| 파일 | 용도 |
| --- | --- |
| `results/report/duration.csv` | 초 단위 실행시간, 배율과 MiB 단위 Old Gen 그래프 입력 |
| `results/report/explain.csv` | 36개 실행계획의 scan, rows와 buffer 비교 |
| `results/report/gc-runs.csv` | 36개 JVM 로그의 run별 pause/old region 요약 |
| `results/report/gc-summary.csv` | Reader/scale/index 12개 조건의 GC 요약 |

## 재현 순서

Java 21과 Docker Compose v2가 준비된 로컬 환경에서 실행한다.

```powershell
.\gradlew.bat --no-daemon test
.\scripts\run-full-matrix.ps1
.\scripts\validate-results.ps1
.\scripts\build-report-data.ps1
```

중단된 matrix는 `.\scripts\run-full-matrix.ps1 -Resume`으로 재개할 수 있다. 새 측정은 기존 공식 결과와 섞지 않고 별도 브랜치나 결과 사본에서 수행해야 한다.

## 해석의 한계

- 한 대의 노트북과 Docker Desktop에서 각 조건을 세 번 측정한 결과다.
- warm-cache 비교이며 OS와 DB cache를 완전히 통제하지 않았다.
- 짧은 100k 조건과 일부 500k 조건의 표준편차가 커 평균 차이를 세밀하게 해석하기 어렵다.
- READY가 ID 앞부분에 모여 있어 상태 선택도와 데이터 분포가 단순하다.
- 공식 36개 EXPLAIN은 보조 인덱스가 아닌 PK 인덱스를 선택했다. 실행시간 ON/OFF 차이를 보조 인덱스 사용 효과로 단정할 수 없다.
- Writer가 외부 I/O 없이 checksum만 계산하므로 실제 정산 쓰기 부하를 포함하지 않는다.
- 처리 중 데이터 변경, 멀티스레드, partitioning과 다른 정렬 키는 범위 밖이다.
- GC 로그의 pause 집계는 JVM 전체이고, Step 구간 메모리는 MXBean peak만 측정했다.
- 세 번의 결과는 이 환경의 관측값이며 다른 장비나 데이터 분포의 보편적 수치가 아니다.
