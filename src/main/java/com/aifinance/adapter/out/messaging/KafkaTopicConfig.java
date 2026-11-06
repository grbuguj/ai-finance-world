package com.aifinance.adapter.out.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 거래 이벤트 토픽을 앱 기동 시 명시적으로 만든다 (KafkaAdmin 이 NewTopic 빈을 보고 생성).
 * 브로커 자동 생성에 맡기면 파티션 수가 브로커 기본값에 좌우되고, 첫 발행이 토픽 생성·리더 선출을 기다리며 실패할 수 있다.
 * 파티션은 3개 — 메시지 키(보내는 계좌 id)로 나뉘므로 같은 계좌의 이벤트 순서는 유지하면서 소비를 병렬화할 수 있다.
 */
@Configuration
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaTopicConfig {

    @Bean
    NewTopic transactionsTopic(@Value("${app.kafka.topic:ai-finance.transactions}") String topic,
                               @Value("${app.kafka.partitions:3}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }
}
