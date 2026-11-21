# 원장 불변성과 잔액 재구성

주장: **원장은 지우거나 고치지 않고, 정정은 반대 분개를 새로 추가한다. 잔액은 원장에서 언제든 다시 만들 수 있다.**
이 문서는 그 주장이 코드에서 예외 없이 성립하도록 한 감사·변경·검증 내용이다.

## 1. 감사: 원장을 UPDATE/DELETE 하는 경로

| 대상 | 변경 경로 (변경 전) | 변경 후 |
|---|---|---|
| `ledger_entries` | `save`(INSERT)와 초기화용 `deleteAll` 뿐 — UPDATE 경로 없음 | 엔티티 `@Immutable`. 삭제 메서드는 `deleteAllForSimulationReset` 한 개 |
| `transfers` | **`Transfer.markRefunded()` 가 원거래 행의 `refunded` 컬럼을 UPDATE** (환불 시), `describe()` 로 사유 수정(미사용), 초기화용 `deleteAll` | `refunded` 컬럼과 `describe()` 제거. 엔티티 `@Immutable`. 환불 여부는 REFUND 거래의 존재로 판정 |
| `accounts.balance` | 거래마다 갱신 (설계상 파생값) | 그대로. 대신 원장으로 재구성하는 도구를 추가 |

즉 "원장에 UPDATE 가 없다"는 말은 변경 전에는 `transfers.refunded` 때문에 완전히 참이 아니었다.

## 2. 변경

- **환불 판정**: `transfers.original_transfer_id` 에 유니크 제약 `uk_transfers_original`. 한 거래는 정정(환불, 투자 정산)을 최대 한 번만
  가질 수 있고, 이중 환불을 DB 가 막는 마지막 방어선이 된다. 환불 후보 조회는 `NOT EXISTS (REFUND 거래)` 로 바꿨고
  `idx_transfers_sender_type(sender_id, tx_type)` 와 유니크 인덱스를 쓴다.
- **중복 정산**: `InvestUseCase.settle` 이 정산 거래를 쓰기 *전에* `investment.assertOpen()` 으로 거절한다
  (유니크 제약이 먼저 걸려 오류 유형이 `DataIntegrityViolationException` 으로 바뀌는 것을 막기 위함).
- **ArchUnit 규칙 3개** (`ArchitectureTest`, 이름이 `원장_불변성_` 으로 시작):
  1. 원장·거래 포트는 `delete/remove/update/set/modify/mark/correct` 메서드를 가질 수 없다. 예외는 `deleteAllForSimulationReset` 하나(이름으로 명시).
  2. `LedgerEntry`, `Transfer` 는 `@Immutable` 이고 `set/update/mark/describe/correct/reset/modify` 메서드가 없다.
  3. 원장·거래 JPA 리포지토리는 영속성 어댑터만 쓴다 (유스케이스·컨트롤러가 repository 로 우회 불가).
- **잔액 재구성**: `POST /ops/rebuild-balances` (기본 dry-run).
  - dry-run: 원장 재계산 값(∑CREDIT − ∑DEBIT)과 현재 잔액이 다른 계좌 목록만 반환. 아무것도 바꾸지 않는다.
  - `apply=true`: 시뮬레이션이 RUNNING 이면 409 로 거부. 그 외에는 불일치 계좌를 ID 오름차순으로, 계좌별 락 안에서 원장 값을
    **다시 계산해** 교정하고(락 전에 구한 값으로 덮어쓰지 않는다), 잔액 캐시를 비우고, 교정 내역을 WARN 로그와 응답에 남긴다.

## 3. 검증 (로컬 H2, 전체 63개 통과)

| 테스트 | 확인 내용 |
|---|---|
| `LedgerImmutabilityTest.환불은_원본…` | 환불 전후로 원본 거래 행과 원본 분개 행의 모든 컬럼이 동일. 거래 +1건, 분개 +2건, 환불 분개는 원본의 반대 방향 |
| `LedgerImmutabilityTest.이중_환불은…` | 두 번째 환불은 앱에서 `IllegalStateException`, JDBC 로 같은 원거래의 REFUND 를 직접 INSERT 해도 `uk_transfers_original` 이 거부 |
| `LedgerImmutabilityTest.환불_후보는…` | 이미 환불된 소비는 후보에서 제외 |
| `ConcurrentRefundTest` (기존) | 같은 소비 건에 환불 20건 동시 요청 → 정확히 1건 성공, 잔액·무결성 유지 (수정 없이 통과) |
| `BalanceRebuildTest` | 잔액 +7원 직접 SQL 로 변조 → 무결성 검사가 불일치 1건 감지 → dry-run 이 차이 −7 보고(교정 안 함) → 실행 중 apply 는 409 → 정지 후 apply 로 교정 → balanced |
| `ArchitectureTest` 3개 | 위 규칙 3개 |

기존 테스트 수정은 3건: `OutboxReliabilityTest`(requeue 직후 재시도 시각을 명시적으로 당겨 같은 밀리초 경합 제거),
`StatsScaleBenchmark`, `LockStrategyBenchmark`(raw INSERT 에서 `refunded` 컬럼 제거).

## 4. DB 수준 가드 (MySQL 트리거)

앱을 우회한 직접 SQL(JDBC·콘솔)로도 원장을 고치거나 지우지 못하게 `transfers`, `ledger_entries` 에 트리거를 건다.

```sql
CREATE TRIGGER trg_ledger_entries_no_update BEFORE UPDATE ON ledger_entries FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ledger_entries is append-only (update blocked)';
CREATE TRIGGER trg_ledger_entries_no_delete BEFORE DELETE ON ledger_entries FOR EACH ROW
  BEGIN IF @afw_allow_reset IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '... (delete blocked)'; END IF; END;
-- transfers 도 동일
```

- **reset 과의 충돌 해결**: 시뮬레이션 초기화는 DELETE 가 필요하다. `deleteAllForSimulationReset` 이 *그 트랜잭션 안에서만* 세션 변수 `@afw_allow_reset` 을
  켜고(같은 트랜잭션 = 같은 커넥션) 끝나면 NULL 로 되돌린다. 다른 세션에는 영향이 없다 (`LedgerGuard.allowResetDuring`).
- **켜고 끄기**: `app.ledger.guard.enabled` (기본 off = H2·로컬 개발). `mysqltest` 프로필은 on 이라 **모든 MySQL 테스트가 트리거가 켜진 상태로 돈다**
  (reset·환불·벤치마크가 트리거와 충돌하지 않는지 함께 검증됨).
- **운영 전제**: 바이너리 로그가 켜진 MySQL 8 에서 SUPER 권한 없는 계정이 트리거를 만들려면 `--log-bin-trust-function-creators=1` 이 필요하다
  (compose 의 mysql 과 테스트 컨테이너에 추가함).
- **테스트**: `MySqlLedgerGuardTest` — 직접 UPDATE/DELETE 4종이 모두 예외, 데이터 불변, 정상 INSERT 와 reset 은 통과, reset 후 다시 차단.
  **MySQL 에서만 동작하므로 이 PC 에서는 실행하지 못했다. CI 의 mysqlTest 잡 통과로 검증한다.**
