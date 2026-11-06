# ADR-0003. 일마감(EOD) 대사로 "유실 0"을 매일 증명한다

- 상태: 채택
- 관련 코드: `ReconciliationUseCase`, `SettlementLedgerJdbcAdapter`, `EndOfDayScheduler`, `ReconciliationTest`

## 문제
Outbox(ADR-0002)로 유실을 **막았다**고 해도, 운영에서 정말 그런지는 **확인**해야 한다.
코드 버그, 수동 데이터 조작, 외부 시스템 장애는 언제든 두 장부를 어긋나게 만들 수 있다.
금융권에서 "시스템이 맞게 돌았다"는 최종 증거는 로그가 아니라 **대사(Reconciliation) 결과**다.

## 결정
매일 00:10(Asia/Seoul)에 전날 영업일 `[00:00, 24:00)` 을 대상으로 은행 원장과 외부 정산 장부를 맞춰 본다.

1. 마감 전 Outbox 를 비워(`drain`) 자정 직전 거래까지 정산 시스템에 도착하게 한다.
2. **총괄 대사**: 양쪽 건수·금액 합계 비교.
3. **명세 대사**: 거래 id 단위로 차이를 4가지로 분류.

| 분류 | 의미 | 판정 |
|---|---|---|
| `MISSING_IN_SETTLEMENT` | 은행에만 있음, Outbox 도 발행 완료 처리 → 진짜 유실 | 조사 필요 |
| `IN_FLIGHT` | 은행에만 있지만 Outbox 에 `PENDING` → 전달 중 | 차이 아님 (오탐 방지) |
| `UNEXPECTED_IN_SETTLEMENT` | 정산 장부에만 있음 | 조사 필요 |
| `AMOUNT_MISMATCH` | 양쪽에 있으나 금액이 다름 | 조사 필요 |

4. 같은 시점의 **복식부기 무결성 검증**(잔액 = 원장, ∑DEBIT = ∑CREDIT, 총액 0)도 함께 기록한다.
5. 결과는 `reconciliation_runs` / `reconciliation_breaks` 에 이력으로 남는다 → `MATCHED` / `PENDING_IN_FLIGHT` / `UNMATCHED`.

### 왜 SQL 로 비교하나
두 장부를 애플리케이션 메모리에 올려 비교하면 거래 수에 비례해 메모리·네트워크를 쓴다.
`LEFT JOIN ... WHERE s.transfer_id IS NULL` 형태의 anti-join 으로 DB 가 **차이만** 돌려주게 했고,
`transfers(created_at)`, `settlement_records(occurred_at)`, `outbox_events(aggregate_id)` 인덱스로 기간·조인 조건을 받친다.

## 검증 (`ReconciliationTest`, H2 + MySQL)
- 전량 전달 → `MATCHED`, 건수·금액 일치, 원장 무결성 OK
- 브로커 장애로 5건 대기 중 → `PENDING_IN_FLIGHT`(누락 0으로 오탐하지 않음) → 복구 후 `MATCHED`
- 정산 장부에 누락 1·금액 변조 1·불명 1건을 일부러 만들면 → 각각 정확한 거래 id 로 탐지, `UNMATCHED`

## 한계와 다음 단계
- 실제 외부 기관 대사는 상대방이 보낸 파일(정산 내역)을 적재한 뒤 비교한다. 이 프로젝트는 같은 DB 의 별도 테이블로 이를 단순화했다.
- 하루 거래가 수천만 건이면 일 단위 한 번이 아니라 시간 단위 파티션 대사 + Spring Batch 청크 처리로 나눈다.
