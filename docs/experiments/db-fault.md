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

## 실측 결과 (CI)

<!-- RESULTS:db-fault -->
미실행 — 이 PC 에는 Docker 가 없어 로컬에서 돌리지 못했다. CI(`db-fault.yml`) 실행 결과가 `experiment-results` 브랜치에 올라오면 채운다.
