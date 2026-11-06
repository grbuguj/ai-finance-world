package com.aifinance.application.service;

import com.aifinance.application.event.TransactionPostedEvent;
import com.aifinance.application.port.out.BalanceCachePort;
import com.aifinance.application.port.out.TransactionEventPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 커밋이 확정된 거래만 캐시/메시징으로 내보낸다.
 * 롤백된 거래가 Kafka 로 나가거나 캐시에 반영되는 일을 원천 차단.
 */
@Component
public class TransactionPostedListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionPostedListener.class);

    private final BalanceCachePort balanceCachePort;
    private final TransactionEventPort transactionEventPort;

    public TransactionPostedListener(BalanceCachePort balanceCachePort, TransactionEventPort transactionEventPort) {
        this.balanceCachePort = balanceCachePort;
        this.transactionEventPort = transactionEventPort;
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
        try {
            transactionEventPort.publish(event);
        } catch (Exception e) {
            log.warn("거래 이벤트 발행 실패 (transfer {}): {}", event.transferId(), e.getMessage());
        }
    }
}
