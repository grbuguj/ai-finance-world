package com.aifinance.adapter.out.messaging;

import com.aifinance.application.event.TransactionPostedEvent;
import com.aifinance.application.port.out.TransactionEventPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 커밋된 거래를 Kafka 토픽으로 비동기 발행.
 * 키 = 보내는 계좌 id → 같은 계좌의 이벤트는 같은 파티션으로 가서 순서가 보장된다.
 * 송금 트랜잭션은 Kafka 응답을 기다리지 않으므로, Kafka 장애가 송금을 막지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaTransactionEventAdapter implements TransactionEventPort {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final AtomicLong published = new AtomicLong();

    public KafkaTransactionEventAdapter(KafkaTemplate<String, String> kafkaTemplate,
                                        @Value("${app.kafka.topic:ai-finance.transactions}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public void publish(TransactionPostedEvent event) {
        kafkaTemplate.send(topic, String.valueOf(event.senderAccountId()), event.toJson())
                .whenComplete((result, ex) -> {
                    if (ex == null) published.incrementAndGet();
                });
    }

    @Override
    public long publishedCount() {
        return published.get();
    }
}
