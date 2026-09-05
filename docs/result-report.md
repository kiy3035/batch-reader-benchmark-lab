# 5단계 공식 측정 결과 보고서

## 결과 상태

2026-09-05에 측정 소스 커밋 `26e9f85761583affa1ac963ff1f83dfd316d05af`로 공식 matrix를 실행했다. 36개 run이 모두 완료됐고 목표 처리 건수, checksum과 인덱스 상태 검증을 통과했다. `results/validation.json`은 run 36개, summary 그룹 12개, GC 로그 36개와 EXPLAIN JSON 36개를 확인했으며 원본 JSON에서 summary 통계도 다시 계산했다.

이 보고서의 시간은 seed나 JVM 부팅이 아닌 Spring Batch Step wall-clock이다. 각 값은 같은 조건을 세 번 실행한 산술평균이다.

## 실행시간

| READY 행 | OFFSET OFF | KEYSET OFF | OFFSET/KEYSET | OFFSET ON | KEYSET ON | OFFSET/KEYSET |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100k | 5.924초 | 4.794초 | 1.236배 | 7.346초 | 5.553초 | 1.323배 |
| 500k | 48.620초 | 12.105초 | 4.017배 | 41.703초 | 13.437초 | 3.104배 |
| 1m | 177.140초 | 19.504초 | 9.082배 | 132.354초 | 18.310초 | 7.228배 |

100k에서는 차이가 1.24~1.32배였지만 1m에서는 7.23~9.08배로 벌어졌다. 이 환경에서는 데이터가 늘수록 OFFSET의 후반 페이지 비용이 전체 Step 시간에 누적됐고 Keyset은 더 완만하게 증가했다.

## 규모 증가 배율

| Reader | Index | 500k / 100k | 1m / 100k | 1m / 500k |
| --- | --- | ---: | ---: | ---: | ---: |
| OFFSET | OFF | 8.208배 | 29.903배 | 3.643배 |
| OFFSET | ON | 5.677배 | 18.018배 | 3.174배 |
| KEYSET | OFF | 2.525배 | 4.068배 | 1.611배 |
| KEYSET | ON | 2.420배 | 3.298배 | 1.363배 |

READY 행이 100k에서 1m으로 10배가 됐을 때 OFFSET 평균은 18.0~29.9배, Keyset 평균은 3.3~4.1배가 됐다. 처리 행 수 자체가 늘어나는 비용은 두 방식에 공통이지만, OFFSET은 페이지 위치가 뒤로 갈수록 앞 행을 통과하는 비용이 추가됐다.

## 반복 편차

1m 조건의 세 run 분포는 다음과 같다.

| Reader | Index | 평균 | 최소 | 최대 | 모표준편차 |
| --- | --- | ---: | ---: | ---: | ---: |
| OFFSET | OFF | 177.140초 | 153.858초 | 219.096초 | 29.727초 |
| OFFSET | ON | 132.354초 | 124.424초 | 136.705초 | 5.616초 |
| KEYSET | OFF | 19.504초 | 18.933초 | 20.549초 | 0.740초 |
| KEYSET | ON | 18.310초 | 17.346초 | 19.054초 | 0.715초 |

OFFSET OFF에는 큰 run 편차가 있었지만 가장 빠른 153.858초도 가장 느린 Keyset 20.549초보다 길었다. 반면 100k와 일부 500k 조건은 세 번만으로 미세한 차이를 설명하기 어려울 정도로 상대 편차가 크다. 따라서 작은 규모의 수 초 차이와 인덱스 ON/OFF 차이는 방향성으로 일반화하지 않는다.

## 인덱스 ON/OFF

| Reader | READY 행 | OFF 평균 | ON 평균 | ON의 변화 |
| --- | ---: | ---: | ---: | ---: |
| OFFSET | 100k | 5.924초 | 7.346초 | 24.0% 증가 |
| OFFSET | 500k | 48.620초 | 41.703초 | 14.2% 감소 |
| OFFSET | 1m | 177.140초 | 132.354초 | 25.3% 감소 |
| KEYSET | 100k | 4.794초 | 5.553초 | 15.8% 증가 |
| KEYSET | 500k | 12.105초 | 13.437초 | 11.0% 증가 |
| KEYSET | 1m | 19.504초 | 18.310초 | 6.1% 감소 |

수치만 보면 큰 scale의 OFFSET에서 ON이 더 빨랐다. 그러나 공식 EXPLAIN 36개는 ON/OFF 모두 `settlement_item_pkey` Index Scan을 선택했고 보조 인덱스 `idx_settlement_item_status_id`는 선택하지 않았다. 1m LAST 페이지 EXPLAIN도 OFFSET ON 232.742ms, OFF 220.363ms로 Step 평균의 방향과 같지 않았다.

따라서 이번 실행은 보조 인덱스가 OFFSET을 25.3% 개선했다고 입증하지 않는다. 교차 순서와 warm-up을 적용했어도 로컬 cache, Docker와 시스템 부하, 세 번의 반복 편차가 남아 있다. 이번 데이터에서 확실히 확인한 사실은 보조 인덱스 존재 여부와 무관하게 선택된 plan이 PK scan이었다는 점이다.

## EXPLAIN

1m에서 각 위치가 반환한 행은 모두 1000개다. `scanActualRows`는 그 1000개를 반환하기 위해 `settlement_item` scan node가 실제로 내보낸 행 수다.

| Index | Reader | 위치 | scanActualRows | 반환 행 | EXPLAIN 실행시간 |
| --- | --- | --- | ---: | ---: | ---: |
| OFF | OFFSET | FIRST | 1,000 | 1,000 | 0.370ms |
| OFF | OFFSET | MIDDLE | 501,000 | 1,000 | 131.198ms |
| OFF | OFFSET | LAST | 1,000,000 | 1,000 | 220.363ms |
| OFF | KEYSET | FIRST | 1,000 | 1,000 | 0.290ms |
| OFF | KEYSET | MIDDLE | 1,000 | 1,000 | 0.387ms |
| OFF | KEYSET | LAST | 1,000 | 1,000 | 0.649ms |
| ON | OFFSET | FIRST | 1,000 | 1,000 | 0.403ms |
| ON | OFFSET | MIDDLE | 501,000 | 1,000 | 81.728ms |
| ON | OFFSET | LAST | 1,000,000 | 1,000 | 232.742ms |
| ON | KEYSET | FIRST | 1,000 | 1,000 | 0.265ms |
| ON | KEYSET | MIDDLE | 1,000 | 1,000 | 0.338ms |
| ON | KEYSET | LAST | 1,000 | 1,000 | 0.382ms |

OFFSET은 FIRST에서 1,000행만 scan했지만 MIDDLE에서 501,000행, LAST에서 1,000,000행을 scan node에서 통과시켰다. Keyset은 `id > lastId`를 PK의 Index Cond로 사용해 세 위치 모두 1,000행이었다. 이 plan 차이가 전체 실행시간 증가 패턴과 같은 방향이다.

1m LAST의 buffer도 차이를 보여준다. OFFSET OFF는 shared read 23,119 blocks, ON은 hit 7,288 + read 15,856 blocks였다. Keyset OFF는 hit 26 + read 2, ON은 hit 27 + read 1 block이었다. hit/read 구성은 cache 상태에 따라 달라질 수 있지만 접근한 block 규모의 차이는 컸다.

모든 plan의 `Rows Removed by Filter`는 0이었다. 공식 seed에서 READY가 ID 앞 구간에 연속 배치됐기 때문에 sampled READY 페이지를 읽는 동안 COMPLETED 행을 거르지 않았다. 상태가 섞여 있거나 READY 선택도가 낮은 데이터에서는 다른 plan과 결과가 나올 수 있다.

## Old Gen과 GC

Step 구간의 `G1 Old Gen` MXBean peak 평균은 다음 범위였다.

| READY 행 | KEYSET OFF / ON | OFFSET OFF / ON |
| ---: | ---: | ---: |
| 100k | 0 / 0 MiB | 0 / 0 MiB |
| 500k | 2.305 / 2.292 MiB | 2.304 / 2.333 MiB |
| 1m | 2.317 / 2.313 MiB | 2.304 / 2.321 MiB |

100k의 0은 측정 불가가 아니라 50ms Step sampling에서 Old Gen 사용이 관찰되지 않았다는 뜻이다. 500k와 1m의 평균은 모두 약 2.29~2.33MiB였고 Reader별 뚜렷한 분리는 없었다. 전체 Entity를 쌓지 않고 페이지와 chunk를 1000으로 제한한 구조와 일치한다.

JVM 전체 GC 로그에서는 모든 run이 G1과 512MiB max heap을 사용했고 Full GC와 OOM은 없었다. scale별 완료 pause 수는 모든 Reader/index에서 100k 12회, 500k 16회, 1m 21회로 같았다. Young Normal pause 수도 각각 3, 7, 12회였다.

1m의 전체 프로세스 pause 합계 평균은 KEYSET OFF/ON 198.961/196.082ms, OFFSET OFF/ON 288.607/195.736ms였다. OFFSET OFF에는 최대 115.152ms pause가 포함됐지만 이 GC 차이는 OFFSET과 Keyset의 Step 시간 차이인 수십~백여 초보다 훨씬 작다. 이 관측은 이번 실행의 주된 시간 차이가 GC pause 때문이라는 설명을 지지하지 않는다. 다만 GC 로그는 JVM 부팅과 종료도 포함하므로 정확한 Step 전용 pause 값은 아니다.

## 정합성

각 scale에서 두 Reader의 checksum은 다음과 같이 같았다.

| READY 행 | checksum |
| ---: | ---: |
| 100k | 202,165,400,000 |
| 500k | 4,121,634,000,000 |
| 1m | 16,000,015,000,000 |

36개 run 모두 목표 건수와 read/write 수가 같았고 1,001건에 대한 재시작 통합 테스트도 통과했다. 따라서 실행시간 비교에 사용한 run은 같은 논리 행 집합을 한 번씩 처리했다.

## 결론과 적용 범위

이 로컬 합성 데이터에서는 Keyset이 100k부터 더 빨랐고 규모가 커질수록 차이가 확대됐다. 1m에서 OFFSET/Keyset 평균 비율은 index OFF 9.082배, ON 7.228배였다. EXPLAIN은 후반 OFFSET 페이지의 scanActualRows가 페이지 위치만큼 증가하고 Keyset은 1000행으로 유지되는 원인을 직접 보여줬다.

메모리 결과는 어느 Reader가 Old Gen을 크게 더 사용한다는 근거를 보이지 않았다. 두 구현 모두 한 페이지와 한 chunk만 유지했기 때문이다. 이번 비교의 핵심 차이는 GC나 누적 Entity 보관보다 DB가 후반 페이지에 도달하는 방식이었다.

Keyset은 고유하고 불변인 정렬 키, 방향이 고정된 순차 처리와 checkpoint 관리가 필요하다. 임의 페이지 이동이나 여러 정렬 조건이 필요한 화면 조회에는 그대로 대체할 수 없다. OFFSET도 작은 데이터에서는 단순하고 충분할 수 있다. 이 결과는 한 환경과 한 데이터 분포의 재현 가능한 관측값이며 모든 쿼리에 대한 보편적 우열을 뜻하지 않는다.

## 근거 파일

- `results/raw-runs.csv`: 36개 실행 원본 표
- `results/summary.csv`: 시간·배율·Old Gen 조건별 집계
- `results/environment.json`: 하드웨어와 소프트웨어 조건
- `results/validation.json`: 완결성과 독립 재계산 결과
- `results/explain/*.json`: 실제 SQL, 바인딩과 PostgreSQL 원본 plan
- `results/gc/*.log`: run별 unified GC 원본 로그
- `results/report/*.csv`: 문서와 그래프를 위한 파생 데이터
