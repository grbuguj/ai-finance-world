package com.aifinance.adapter.out.messaging;

import com.aifinance.application.event.OutboundMessage;
import com.aifinance.application.port.out.TransactionEventPort;
import com.aifinance.external.settlement.SettlementInbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Kafka 없이 실행할 때(app.kafka.enabled=false, 기본값)의 브로커 대체 구현.
 * 메시지를 외부 정산 시스템(SettlementInbox)으로 같은 JVM 안에서 바로 전달한다.
 *
 * Kafka 와 똑같이 장애 주입기를 거치므로, Docker 없이도 Outbox 유실 방지·중복 제거를 시연/테스트할 수 있다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class InProcessTransactionEventAdapter implements TransactionEventPort {

    private final SettlementInbox inbox;
    private final BrokerFaultInjector faults;

    public InProcessTransactionEventAdapter(SettlementInbox inbox, BrokerFaultInjector faults) {
        this.inbox = inbox;
        this.faults = faults;
    }

    @Override
    public void publish(OutboundMessage message) {
        faults.beforeSend();
        inbox.receive(message.payload());
        faults.afterSend();
    }
}
