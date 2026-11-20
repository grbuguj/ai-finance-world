# ADR-0006. 시뮬레이션은 가상 시계(VirtualClock)로 돈다

- 상태: 채택
- 관련 코드: `VirtualClock`, `EndOfDayService`, `SimulationEngine.maybeRunVirtualEod`, `Transfer.occurredAt`,
  `LedgerPostingService`, `SettlementLedgerJdbcAdapter`, `ReconciliationUseCase.reconcileToday`, `EndOfDayScheduler`

## 문제
기존 일마감(ADR-0003)은 서버의 실제 시계(`app.eod.cron = 0 10 0 * * *`, Asia/Seoul 매일 00:10)로 돌았다.
시뮬레이션은 에이전트 수·속도에 따라 하루치 거래를 몇 초~몇 분 만에 만들어내는데, 대사는 실제 자정에만 도니
"하루"라는 개념이 시뮬레이션과 전혀 무관했다 — 발표 중 장애 주입 → 복구 → 대사 일치 흐름을 시연하려면
실제 자정을 기다리거나 서버 시계를 조작해야 했다.

## 결정

### 1. 가상 시각 = 시작 시각 + 틱 수 × 분/틱
`VirtualClock` 이 틱 수의 유일한 소유자다. `app.simulation.start-date`(기본 `2026-01-01T00:00`)에서 시작해,
틱마다 `app.simulation.minutes-per-tick`(기본 10분)만큼 전진한다. 144틱 = 가상 하루.
실제 wall-clock 과는 완전히 독립적이라, 시뮬레이션을 멈추거나 빠르게 돌려도 가상 하루는 틱 수에만 비례한다.

`SimulationEngine` 이 틱마다 `virtualClock.advance()` 를 호출해 진행시킨다. `VirtualClock` 은 `SimulationEngine`
을 모른다 — `SimulationEngine → (TransferUseCase 등) → LedgerPostingService → VirtualClock` 으로 이미 참조가
흐르고 있어, 거꾸로 `VirtualClock → SimulationEngine` 의존을 만들면 순환 의존이 되기 때문이다. `LedgerPostingService`
는 그래서 "지금이 몇 번째 틱인지"가 아니라 "지금이 가상으로 언제인지"만 `VirtualClock.now()` 로 묻는다.

### 2. 대사 기준 시각 컬럼: `transfers.occurred_at` (신규, 가상 시각) vs `created_at` (기존, 실제 시각)
`Transfer.createdAt`(`@CreatedDate`, 실제 생성 시각)은 감사·운영 로그용으로 그대로 둔다. 거래가 "언제 일어난
일"인지를 가르는 새 컬럼 `occurredAt` 을 추가해 `LedgerPostingService` 가 기장 시점에 `virtualClock.now()` 로
채운다. 이 값은 `TransactionPostedEvent.occurredAt` 을 통해 외부 정산 장부(`SettlementRecord.occurredAt`)에도
그대로 전달되므로, 은행 원장과 정산 장부가 **같은 가상 시각 기준**을 공유한다 — 전달 경로(Outbox → 인메모리/Kafka
→ SettlementInbox) 코드는 한 줄도 건드리지 않고 값만 바뀐다.

대사 쿼리(`SettlementLedgerJdbcAdapter`)의 기간 조건을 `transfers.created_at` → `transfers.occurred_at` 으로
바꿨다 (`settlement_records.occurred_at` 은 원래부터 이 의미였다). `idx_transfers_occurred_at` 인덱스를 추가하고,
`idx_transfers_created_at` 은 감사 조회용으로 남겨둔다.

**대안으로 검토했으나 택하지 않은 것**: `@CreatedDate`(JPA Auditing)가 제공하는 `DateTimeProvider` 를 가상
시계로 바꿔 `createdAt` 자체를 가상화하는 방법. 거부한 이유는 (1) "언제 DB 에 적재됐나"(운영 로그 질문)와
"언제 거래가 발생했나(영업일 판별)"는 서로 다른 질문인데 하나의 컬럼이 둘 다 떠안게 되고, (2) Auditing 의
`DateTimeProvider` 는 엔티티 전역(Outbox, ReconciliationRun 등)에 적용되어 영향 범위가 이 기능이 필요한
"거래"를 넘어서기 때문이다.

### 3. 일마감 트리거: 가상 자정 + 10분 유예, 실제 시계 cron 은 끔
기존 ADR-0003 의 "00:10 유예"(자정 직전 거래의 전달 지연 흡수) 취지를 그대로 가상 시계에 옮겼다.
`SimulationEngine.maybeRunVirtualEod()` 가 매 틱마다 가상 시각을 확인해, 새 가상 하루의 00:10 을 지나는
틱에서 **직전 가상 하루**(`[전날 00:00, 그날 00:00)`)에 대해 `EndOfDayService.closeBusinessDay(businessDate)`
를 호출한다. 이미 그 영업일을 마감했으면(`lastEodBusinessDate`) 같은 날 안에서는 다시 돌지 않는다.

실제 처리(Outbox `drain` + `reconciliationUseCase.reconcileDay`)는 `EndOfDayService` 로 뽑아내 실제 시계
스케줄러(`EndOfDayScheduler`, `app.eod.cron`)와 공유한다. `app.eod.enabled` 기본값을 `false` 로 바꿔 시뮬레이션
에서는 실제 시계 cron 이 돌지 않게 했다 — 켜 두면 실제 자정에도 한 번 더 돌면서 가상 자정 트리거와 중복 실행될
수 있기 때문이다. 실제 시계로 운영하는 별도 배포가 있다면 그 환경에서만 `true` 로 켜면 된다.

수동 실행 경로(`POST /ops/reconciliation?date=...`, `ReconciliationUseCase.reconcileDay`)는 그대로 둬서
날짜를 지정해 가상의 특정 영업일을 다시 대사할 수 있다. "지금 당장 점검"용 `reconcileToday()` 도 실제
`LocalDate.now()` 대신 `virtualClock.now()` 기준 "가상 오늘"을 보도록 맞췄다 — 거래 자체가 가상 시각으로
찍히는데 점검만 실제 시각 창을 보면 그 사이 늘 어긋나기 때문이다.

### 4. 오탐 방지 로직은 그대로
대사의 분류 기준(`MATCHED`/`PENDING_IN_FLIGHT`/`UNMATCHED`, Outbox `PENDING` → "전달 중")은 전혀 바뀌지 않았다.
브로커가 죽어 있는 중에 가상 자정이 지나도, 그 틱에서 도는 대사는 `drain` 이 실패해도 그대로 실행되고
—발행되지 못한 Outbox 건은 `PENDING` 상태 그대로이므로 쿼리가 `IN_FLIGHT` 로 정확히 분류한다. "가상 하루 경과
수"(`virtualDaysElapsed`, `GET /simulation/summary`)는 대사 **판정 결과와 무관하게** `closeBusinessDay` 가
예외 없이 끝난 횟수로 센다 — "오늘 거래가 몇 건이었나"가 아니라 "며칠째 돌았나"를 보여주는 값이기 때문이다.

## 트레이드오프
- 틱 간격(`minutes-per-tick`)이 1440(하루)을 나누어떨어지지 않으면 "00:10" 시각이 정확히 나오지 않을 수 있다
  (예: 틱이 13분이면 00:00, 00:13, 00:26 ... 처럼 00:10 을 건너뛸 수 있음). `maybeRunVirtualEod` 는 "00:10
  **정각**"이 아니라 "00:10 **이상**이 된 첫 틱"에서 돈다(`isBefore` 비교)로 이 문제를 피했다 — 기본값(10분)은
  144 로 나누어떨어지므로 영향이 없고, 다른 값을 쓰더라도 마감이 하루씩 밀리지 않는다.
- `VirtualClock` 은 싱글턴 상태(틱 수 하나)를 전역으로 공유한다. 여러 시뮬레이션을 한 프로세스에서 동시에
  돌리는 멀티테넌시는 이 설계로는 안 된다 — 지금은 "서버 하나 = 시뮬레이션 하나"라 문제되지 않는다.
- `payday-interval-ticks`(급여 지급 주기)는 가상 날짜가 아니라 틱 그대로 둔다. 급여는 "영업일 경계"와
  맞아떨어질 필요가 없는 주기적 이벤트라 가상 시간 도입의 대상이 아니라고 판단했다.
