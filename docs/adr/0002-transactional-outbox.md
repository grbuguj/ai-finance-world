# ADR-0002. 거래 이벤트는 Transactional Outbox 로 발행하고, 소비자는 멱등하게 만든다

- 상태: 채택
- 관련 코드: `LedgerPostingService`, `OutboxEvent`, `OutboxRelayService`, `SettlementInbox`, `OutboxReliabilityTest`

## 문제
기존에는 거래가 커밋된 직후(`@TransactionalEventListener(AFTER_COMMIT)`) Kafka 로 이벤트를 보내고, 실패하면 로그만 남겼다.

```
[DB 커밋 ✅] ──(이 사이에 앱 종료 / 브로커 장애)──▶ [Kafka 발행 ❌]  → 이벤트 영구 유실
```
은행 원장에는 거래가 있는데 외부 정산 시스템에는 없다. 로그 한 줄 외에는 아무도 모르고, 다시 보낼 방법도 없다.
DB 커밋과 메시지 발행은 서로 다른 시스템이라 하나의 트랜잭션으로 묶을 수 없다는 것(dual write 문제)이 근본 원인이다.

## 결정
1. **같은 트랜잭션에 기록**: `LedgerPostingService` 가 거래·분개를 쓰는 트랜잭션 안에서 `outbox_events` 에 이벤트를 INSERT 한다.
   커밋되면 둘 다 남고, 롤백되면 둘 다 사라진다.
2. **릴레이가 재시도하며 발행**: `OutboxRelayService` 가 100ms 마다 `PENDING` 이벤트를 id 순으로 읽어 **동기 발행**(브로커 ack 확인)하고,
   성공하면 `PUBLISHED`, 실패하면 지수 백오프(0.5s → 30s)로 다시 시도한다. 10회 실패하면 `DEAD` 로 격리하고 지표·로그로 알린다.
3. **순서 보장**: 배치 중 하나라도 실패하면 그 배치는 거기서 멈춘다. 뒤 이벤트가 앞 이벤트를 추월하지 않는다.
   Kafka 메시지 키는 보내는 계좌 id 라서 같은 계좌의 이벤트는 같은 파티션, 같은 순서로 쌓인다.
4. **멱등 소비자**: 릴레이는 "보냈는데 `PUBLISHED` 표시 전에 죽는" 경우 같은 이벤트를 다시 보낸다(at-least-once).
   외부 정산 시스템(`SettlementInbox`)은 **거래 id 를 PK** 로 장부에 기록하므로 몇 번 받아도 한 줄만 남는다.
   → 발행은 at-least-once, 효과는 정확히 한 번(effectively-once).
5. **다중 서버**: 릴레이는 분산 락(`lock:outbox-relay`)을 잡은 인스턴스 하나만 돈다. 단일 서버에서는 JVM 락으로 동작한다.
6. **소비자 실패 격리**: 정산 처리가 계속 실패하는 메시지는 0.5초 간격 3회 재시도 후 `<topic>.DLT` 로 옮긴다.
   Spring Kafka 기본 동작(재시도 후 로그 남기고 건너뛰기)이면 그 거래가 정산 장부에서 조용히 빠지기 때문이다.
7. **보관 기간**: `PUBLISHED` 는 7일 뒤 1,000건 단위로 정리한다. 대사가 Outbox 상태로 "전달 중/유실"을 가르므로 대사 기간(1일)보다 길게 둔다.

## 검증 (`OutboxReliabilityTest`, H2 + MySQL)
| 시나리오 | 결과 |
|---|---|
| 브로커 장애 중 송금 30건 → 복구 | 장애 중에도 송금 성공, 이벤트 30건 대기 → 복구 후 전량 발행, **커밋된 거래 수 = 정산 장부 행 수** |
| ACK 유실 3회 (브로커는 받았지만 응답 소실) | 같은 이벤트가 4번 도착 → 중복 3건 제거, 정산 장부는 거래당 1행 |
| 잔액 부족으로 롤백된 거래 | Outbox 에도 남지 않음 (외부로 새지 않음) |
| 양방향 동시 송금 200건 | 거래 ↔ 이벤트 1:1, 이벤트 없는 거래 0건 |
| 10회 연속 실패 | `DEAD` 격리 → 운영자 재처리(`POST /ops/outbox/requeue-dead`) 후 정상 전달 |
| 실제 Kafka 컨테이너 일시정지 → 재가동 (`FullInfraOutboxIntegrationTest`) | 정지 중 발행 0·대기 10건·대사 `PENDING_IN_FLIGHT` → 재가동 후 전량 도착·`MATCHED` |
| 독 메시지 | DLT 에 예외 헤더와 함께 보존, 이후 정상 메시지 처리 계속 |

## 검토한 대안
| 대안 | 기각 이유 |
|---|---|
| AFTER_COMMIT 직접 발행 (기존) | 커밋과 발행 사이 장애 시 유실. 재전송 근거 데이터가 없음 |
| 커밋 전(트랜잭션 안에서) 발행 | 롤백된 거래가 외부로 나간다 — 유실보다 더 나쁨 |
| Kafka 트랜잭션 / XA(2PC) | DB 와 브로커를 묶는 분산 트랜잭션은 코디네이터 장애·성능 비용이 크고, 소비자 멱등성은 어차피 필요 |
| CDC (Debezium 으로 binlog 읽기) | 운영 인프라(Kafka Connect)가 추가로 필요. 학부 프로젝트 규모에서는 폴링 릴레이가 단순하고 충분. 트래픽이 커지면 전환 후보 |

## 트레이드오프
- **발행 지연**: 커밋 후 최대 폴링 주기(100ms)만큼 늦게 나간다. 정산·알림 용도로는 문제없고, `afw_outbox_publish_lag_seconds` 로 감시한다.
- **추가 쓰기**: 거래 1건당 INSERT 1건, UPDATE 1건이 늘어난다. 발행 완료 행은 보존 기간 후 정리 대상이다.
- **Head-of-line blocking**: 한 이벤트가 계속 실패하면 뒤 이벤트도 같이 기다린다. 그래서 최대 재시도 후 `DEAD` 로 빼서 대기열을 푼다.
- **처리량 상한**: 순서 보장을 위해 한 건씩 ack 를 기다리며 보낸다(브로커 왕복 수 ms → 초당 수백 건).
  이 시뮬레이션 규모에서는 충분하지만, 더 필요하면 파티션 키별로 릴레이를 나누거나 배치 비동기 전송 + 일괄 확인으로 바꿀 수 있다.

## 장애 주입으로 직접 확인하기 (local 프로필)
```bash
curl -X POST localhost:8080/chaos/broker/down      # 브로커 장애
curl localhost:8080/ops/outbox                     # pending 증가 확인 (거래는 계속 성공)
curl -X POST localhost:8080/chaos/broker/up        # 복구 → pending 이 0 으로 수렴
curl -X POST localhost:8080/ops/reconciliation     # 대사 결과 MATCHED
```
