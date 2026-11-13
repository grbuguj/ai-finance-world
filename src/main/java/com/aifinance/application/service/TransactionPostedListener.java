package com.aifinance.application.service;

import com.aifinance.application.event.TransactionPostedEvent;
import com.aifinance.application.port.out.BalanceCachePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 커밋이 확정된 거래만 잔액 캐시에 반영한다. 롤백된 거래는 캐시에 들어가지 않는다.
 *
 * 외부 시스템(Kafka) 발행은 더 이상 여기서 하지 않는다.
 * AFTER_COMMIT 에서 바로 보내면 "커밋은 됐는데 발행 전에 죽는" 순간 이벤트가 유실되므로,
 * LedgerPostingService 가 Outbox 에 기록하고 OutboxRelayService 가 재시도하며 발행한다.
 */
@Component
public class TransactionPostedListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionPostedListener.class);

    private final BalanceCachePort balanceCachePort;

    public TransactionPostedListener(BalanceCachePort balanceCachePort) {
        this.balanceCachePort = balanceCachePort;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(TransactionPostedEvent event) {
        try {
            balanceCachePort.put(event.senderAccountId(), event.senderBalanceAfter(), event.transferId());
            balanceCachePort.put(event.receiverAccountId(), event.receiverBalanceAfter(), event.transferId());
        } catch (Exception e) {
            // 캐시는 보조 수단 - 실패해도 거래는 이미 커밋됨. 조회 시 DB로 폴백한다.
            log.warn("잔액 캐시 갱신 실패 (transfer {}): {}", event.transferId(), e.getMessage());
        }
    }
}
