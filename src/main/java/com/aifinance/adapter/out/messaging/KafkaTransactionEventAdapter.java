package com.aifinance.adapter.out.messaging;

import com.aifinance.application.event.OutboundMessage;
import com.aifinance.application.port.out.TransactionEventPort;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Kafka 발행 어댑터 (app.kafka.enabled=true).
 *
 * - 키 = 보내는 계좌 id → 같은 계좌의 이벤트는 같은 파티션 → 순서 보장
 * - 동기 전송: ack(acks=all) 를 받아야 반환 → Outbox 가 PUBLISHED 로 표시해도 되는 근거
 * - 프로듀서 멱등성(enable.idempotence=true): 프로듀서 내부 재시도로 인한 브로커 측 중복은 Kafka 가 제거
 *   (릴레이 재발행으로 인한 중복은 소비자가 거래 id 로 제거)
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaTransactionEventAdapter implements TransactionEventPort {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final BrokerFaultInjector faults;
    private final String topic;
    private final long sendTimeoutMs;

    public KafkaTransactionEventAdapter(KafkaTemplate<String, String> kafkaTemplate,
                                        BrokerFaultInjector faults,
                                        @Value("${app.kafka.topic:ai-finance.transactions}") String topic,
                                        @Value("${app.kafka.send-timeout-ms:3000}") long sendTimeoutMs) {
        this.kafkaTemplate = kafkaTemplate;
        this.faults = faults;
        this.topic = topic;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Override
    public void publish(OutboundMessage message) {
        faults.beforeSend();
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, message.key(), message.payload());
        record.headers().add("event-id", message.eventId().getBytes(StandardCharsets.UTF_8));
        try {
            kafkaTemplate.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BrokerFaultInjector.BrokerUnavailableException("Kafka 전송 중단", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new BrokerFaultInjector.BrokerUnavailableException("Kafka 전송 실패: " + e.getMessage(), e);
        }
        faults.afterSend();
    }
}
