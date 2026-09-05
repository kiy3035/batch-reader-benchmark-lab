# 1단계 기술 결정

## 버전 고정

- Java toolchain은 프로젝트 명세에 따라 21로 고정했다.
- Spring Boot 3.x의 현재 유지보수 버전인 3.5.16을 고정했다. 이 BOM이 Spring Batch 5.2.6과 Hibernate 6.6.53.Final을 관리한다.
- Gradle Wrapper는 Java 21과 Spring Boot 3.5 계열을 지원하는 8.12.1로 고정했다.
- PostgreSQL 이미지는 `latest` 대신 `postgres:16.15-alpine`을 고정했다. 테스트와 Compose가 같은 버전을 사용한다.

동적 버전을 쓰지 않아 새 환경에서도 의존성 변화로 실험 조건이 흔들리지 않게 했다.

## 스키마 소유권

실험 테이블, seed 함수, Spring Batch 메타데이터는 모두 Flyway가 관리하고 Hibernate는 `ddl-auto=validate`만 사용한다. Batch 5.2.6의 공식 PostgreSQL 스키마를 V1에 고정했으며 Boot의 반복 초기화는 끈다. 같은 DB에서 애플리케이션을 재실행해도 Flyway가 이미 적용한 메타데이터와 충돌하지 않는다. Spring Batch 버전을 올릴 때는 공식 migration과 이 스키마의 차이를 함께 검토해야 한다.

## smoke seed

Flyway V3에 READY 4건과 COMPLETED 1건을 넣었다. ID `3` 등을 의도적으로 비워 이후 Reader 테스트가 ID 연속성을 가정하지 않게 한다. 이 데이터는 애플리케이션 기동과 최소 Job을 빠르게 검증하기 위한 것이며 성능 결과가 아니다.

## 대량 데이터 생성 기반

`prepare_settlement_seed(ready_rows)` PostgreSQL 함수와 PowerShell/Bash 진입점을 선택했다. `generate_series`가 DB 안에서 bounded SQL 한 번으로 행을 만들므로 Java에서 최대 100만 Entity를 생성하거나 `saveAll`하지 않는다.

공식 scale에서 READY 건수는 인자와 정확히 같고, 비교 대상인 COMPLETED는 READY의 10%다. 다음 값은 ID로만 계산되어 같은 입력에서 동일하다.

- `customer_id`: `100000 + id % 10000`
- `amount`: `(id * 37 % 1000000) / 100`
- `settled_at`: 고정 기준 시각 + ID초
- `reference_no`, `payload`: ID를 포함한 고정 형식

함수는 테스트 편의를 위해 0부터 1,000,000까지 허용하지만 사용자용 scale 스크립트는 명세의 세 크기만 허용한다. 실제 대량 적재는 5단계에서 실행하고 원본 조건과 시간을 기록한다.

## 최소 Batch Job

1단계 `smokeJob`은 단일 tasklet Step에서 READY 행 수가 0보다 큰지 확인한다. Flyway, DataSource, Batch JobRepository와 트랜잭션이 함께 작동한다는 기동 검증용이다. Reader와 Processor/Writer는 공정한 정합성 설계가 필요한 2단계에서 구현한다.

## 로컬 설정

Compose 기본 계정 값은 `.env.example`에 공개 가능한 로컬 예시로만 둔다. 실제 비밀값이나 외부 서비스는 사용하지 않는다. 데이터 volume은 Git에서 제외하며 OS page cache 초기화나 관리자 권한 작업은 수행하지 않는다.
