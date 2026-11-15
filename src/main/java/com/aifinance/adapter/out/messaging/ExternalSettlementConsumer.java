package com.aifinance.adapter.out.messaging;

import com.aifinance.external.settlement.SettlementInbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 외부 정산 시스템의 Kafka 수신부 (app.kafka.enabled=true).
 * 실제 처리(중복 제거 + 정산 장부 기록)는 SettlementInbox 가 한다.
 * 처리 중 예외가 나면 오프셋이 커밋되지 않아 재전달된다 → 역시 SettlementInbox 가 중복을 제거.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class ExternalSettlementConsumer {

    private final SettlementInbox inbox;

    public ExternalSettlementConsumer(SettlementInbox inbox) {
        this.inbox = inbox;
    }

    @KafkaListener(topics = "${app.kafka.topic:ai-finance.transactions}", groupId = "external-settlement")
    public void onTransaction(String message) {
        inbox.receive(message);
    }
}
