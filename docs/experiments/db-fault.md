# DB 쪽 장애 실험

지금까지는 브로커 장애만 주입했다. 여기서는 DB 쪽 두 가지를 다룬다: (a) 락 대기 시간 초과, (b) MySQL 재시작.
**실측은 실제로 돌린 값만 적는다. 돌리지 못한 값은 "미실행"으로 표시한다.**

## (a) 락 대기 시간 초과

시나리오: 별도 커넥션이 한 계좌 행을 `SELECT ... FOR UPDATE` 로 잡아 둔 채, 그 계좌로 `POST /transfers` 를 보낸다.
락 대기 한도는 H2 에서 `LOCK_TIMEOUT=1500ms`, MySQL 에서 `innodb_lock_wait_timeout=2` 로 줄였다.

### 변경 전 응답 (실측, 로컬 H2)

| 항목 | 값 |
|---|---|
| 응답 코드 | **500 Internal Server Error** (본문에 재시도 안내 없음) |
| 응답까지 걸린 시간 | 2,045 ms (락 대기 한도 1.5초 + 처리) |

락 획득 실패 예외(`PessimisticLockingFailureException` 계열)에 대한 핸들러가 없어 서버 오류로 나갔다.
클라이언트는 이것이 "버그"인지 "잠깐 바쁜 것"인지 구분할 수 없다.

### 변경 후 응답 (실측, 로컬 H2)

| 항목 | 값 |
|---|---|
| 응답 코드 | **503 Service Unavailable** + `Retry-After: 1` |
| 본문 | "같은 계좌를 처리 중인 다른 거래 때문에 지연되어 거절되었습니다. 기록은 남지 않았으니 잠시 후 다시 시도해 주세요." |
| 응답까지 걸린 시간 | 1,901 ms |
| 부분 기록 | 없음 — 거래 수, 원장 수, Outbox 수, 모든 계좌 잔액이 요청 전과 같음 |
| 무결성 | 유지 (`balanced=true`) |
| 락 해제 후 같은 요청 | 200 성공 |

- 변경: `ApiExceptionHandler` 가 `PessimisticLockingFailureException`(락 대기 초과, 데드락 포함)과 `QueryTimeoutException` 을 503 + `Retry-After` 로 매핑.
- 테스트: `LockTimeoutResponseTest`(H2), `MySqlLockTimeoutResponseTest`(MySQL, 같은 시나리오).
- MySQL(InnoDB): CI 의 mysqlTest 잡이 통과해야 검증된 것이다 — 아래 "CI 결과" 참고. 응답 시간 같은 수치는 CI 로그를 이 PC 에서 읽을 수 없어 기록하지 못했다(통과/실패만 확인 가능).

### 함께 고친 것: 커넥션 풀 대기 시간

| 설정 | 변경 전 | 변경 후 |
|---|---|---|
| `spring.datasource.hikari.connection-timeout` | 30,000 ms (기본값) | 3,000 ms |
| `spring.datasource.hikari.validation-timeout` | 5,000 ms (기본값) | 2,000 ms |
| MySQL URL | (없음) | `connectTimeout=2000&socketTimeout=15000` |

DB 가 멈추면 요청이 풀에서 커넥션을 기다리며 30초씩 매달리는 것이 기본 동작이었다. 이를 3초로 줄이고,
`CannotCreateTransactionException`/`DataAccessResourceFailureException` 을 503 + `Retry-After: 2` 로 매핑했다.

## (b) MySQL 재시작 (부하 중)

도구: `tools/experiments/db_restart.py`, workflow `db-fault.yml` (수동 실행, 결과는 `experiment-results` 브랜치).
시나리오: 동시 요청 20개가 송금을 계속 보내는 중에 `docker stop` → 20초 유지 → `docker start`.
기록: 구간별(정지 중 / 기동 중 / 복구 후) 요청·실패 수와 응답 코드 분포·지연, 복구까지 걸린 시간, 사후 Outbox·무결성·대사.

**결과: 아래 "실측 결과" 절 참고 (미실행이면 그렇게 적혀 있다).**

## 실측 결과 (CI: GitHub Actions ubuntu-latest, docker compose, 20 스레드, MySQL 20초 정지)

원본: `experiment-results` 브랜치 `db-fault/44700473014/db-fault.json` (커밋 `19eef5d` 기준 실행).

| 타임라인 | 값 |
|---|---|
| `docker stop` 소요 | 3.0 초 |
| MySQL 정지 유지 | 20.0 초 |
| `docker start` → DB 응답 가능(`mysqladmin ping`) | 1.4 초 |
| `docker start` → 앱의 첫 성공 응답 | **9.2 초** |
| DB 응답 가능 → 첫 성공 | 7.8 초 (풀이 죽은 커넥션을 버리고 다시 연결하는 시간 + 실패 요청이 3초씩 대기하는 간격) |

| 구간 | 요청 | 성공 | 실패 | 응답 코드 분포 | 실패 지연(p50 / 최대) |
|---|---|---|---|---|---|
| 장애 전 | 3,430 | 3,430 | 0 | 200:3,430 | (지연 p50 75ms, p95 186ms) |
| `docker stop` 진행 중 | 38 | 5 | 33 | 200:5, **503:26, 500:7** | 3,007 / 3,022 ms |
| **DB 정지 중** | 137 | 0 | **137** | **503:137** | 3,003 / 3,008 ms |
| DB 기동 중 (start→첫 성공) | 60 | 20 | 40 | 503:40, 200:20 | 3,002 / 3,004 ms |
| 복구 후 | 6,404 | 6,404 | 0 | 200:6,404 | (지연 p50 44ms, p95 203ms) |

해석:
- **요청은 오래 매달리지 않는다**: DB 정지 중 137건이 모두 약 3.0초(= Hikari `connection-timeout` 3초) 만에 503 으로 응답했다.
  변경 전 기본값(30초)이었다면 같은 구간에서 요청마다 30초씩 매달렸을 것이다(이 값은 이번에 측정하지 않았다 — 설정값 기반 설명).
- 복구 후에는 풀이 스스로 회복해 오류 0건으로 돌아왔고 지연도 장애 전 수준이다.
- **500 7건**: 모두 `docker stop` 진행 중(MySQL 이 종료되는 순간)에 발생했다. 이 실행의 로그 꼬리에는 예외 클래스가 남지 않아 **원인을 확정하지 못했다.**
  추정은 커밋 시점 연결 끊김(`TransactionSystemException` 등)이다. 대응: `TransactionSystemException` 을 503 으로 매핑하고, 매핑되지 않은
  예외는 클래스명을 ERROR 로그에 남기도록 했다(다음 실행에서 `app-errors.log` 로 수집).

### 사후 검증과 발견한 결함

| 항목 | 값 |
|---|---|
| Outbox | 대기 0, 발행 9,981, DEAD 0 (복구 후 5.1초 만에 비움) |
| 원장 무결성 | `balanced=true` |
| **대사** | **UNMATCHED — 은행 9,981건 / 정산 9,979건, 누락 2, 전달 중 0** |

**원장은 맞고 Outbox 도 비었는데, 정산 장부에서 2건이 영구히 빠졌다.** 대사의 "누락"은 Outbox 가 PUBLISHED 인데 정산 장부에 없는 건 — 즉 *진짜 유실*이다.
앱 로그에 `Record in retry and not yet recovered` 가 남아 있다. 원인은 정산 수신(Kafka 소비자)이다:

- Spring Kafka 기본 오류 처리기는 재시도 9회 후 그 레코드를 **건너뛴다**. MySQL 이 멈춘 동안 `SettlementInbox` 저장이 실패하면 재시도가 모두 실패하고 레코드가 버려진다.
- 정산 수신이 따라잡는 동안 "누락"이 6,668 → … → 2 로 줄어들다 2에서 멈춘 것이 로그에서 확인된다(소비 지연은 회복됐고, 건너뛰어진 2건만 남음).
- 내가 Hikari `connection-timeout` 을 30초 → 3초로 줄인 것이 재시도 소진을 앞당겼을 가능성이 있다(변경 전에는 시도 하나가 30초씩 걸려 포기까지 훨씬 오래 걸림). 이 인과는 측정하지 않았다.
- **수정**: `SettlementConsumerErrorConfig` — 일시적 오류는 지수 백오프(0.5s→5s)로 시간 제한 없이 재시도(파티션은 멈추지만 순서 보존, 멱등이라 안전), 고칠 수 없는 메시지(JSON 깨짐·필수값 누락)만 즉시 건너뜀.
  회귀 테스트: `KafkaConsumerErrorHandlingTest`. **수정 후 재실행 결과는 아래 "재실행" 참고.**

이 결함은 단위·통합 테스트로는 잡히지 않았다(브로커 장애만 주입했고, DB 가 소비자 쪽에서 죽는 경우는 없었다). 대사가 잡아낸 실제 유실이다.

### 재실행 (수정 후) — 유실 수정 확인

원본: `experiment-results` 브랜치 `db-fault/44702013216/` (커밋 `5b31278`, 같은 조건: 20 스레드, MySQL 20초 정지).

| 항목 | 수정 전 | 수정 후 |
|---|---|---|
| 사후 대사 | **UNMATCHED** (은행 9,981 / 정산 9,979, 누락 2) | **MATCHED** (은행 9,561 = 정산 9,561, 누락 0, 전달 중 0) |
| 중복 수신(멱등이 흡수) | 0 | 200 (재시도로 다시 도착한 레코드를 멱등 소비자가 흡수) |
| Outbox | 대기 0, 발행 9,981, DEAD 0 | 대기 0, 발행 9,561, DEAD 0 (비우는 데 6.1초) |
| 원장 무결성 | balanced | balanced |
| DB 정지 중 응답 | 137건 모두 503 (~3.0초) | 120건 모두 503 |
| `docker start` → 첫 성공 | 9.2초 | 9.2초 |

**유실은 사라졌다.** 소비자가 DB 복구까지 재시도하다 이어서 처리했고(그래서 중복 200건이 생겼다), 대사가 MATCHED 로 돌아왔다.
이 실험은 한 번씩 돌린 값이라 "수정 전 2건 유실이 항상 재현된다"고 일반화하지는 않는다 — 수정 전 1회에서 2건, 수정 후 1회에서 0건이다.

**500 의 원인은 `JpaSystemException`** 으로 확인됐다(`app-errors.log` 6건). 이번에는 장애 직전 구간에도 3건이 있었다 — 연결이 끊기는 순간 커밋/조회가
`JpaSystemException`(Hibernate 가 연결 오류를 감싼 것)으로 나와 503 매핑을 비껴갔다.
**아직 고치지 않았다**: `JpaSystemException` 은 연결 오류 외의 영속성 오류도 감싸므로, 원인 체인에 연결 오류(SQLState 08xx, Communications 계열)가 있을 때만 503 으로
매핑해야 한다. 지금은 500 + 클래스명 로그로만 남는다.
