package com.aifinance.adapter.out.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 토픽·소비자 오류 처리 설정 (app.kafka.enabled=true).
 *
 * 토픽: 앱 기동 시 명시적으로 만든다. 브로커 자동 생성에 맡기면 파티션 수가 브로커 기본값에 좌우되고,
 *       첫 발행이 토픽 생성·리더 선출을 기다리며 실패할 수 있다.
 *       파티션 3개 — 메시지 키(보내는 계좌 id)로 나뉘므로 같은 계좌의 순서는 유지하면서 소비를 병렬화할 수 있다.
 *
 * 독(poison) 메시지: 정산 처리가 계속 실패하는 메시지를 기본 동작대로 "몇 번 재시도 후 로그 남기고 건너뛰기" 하면
 *       그 거래는 정산 장부에서 조용히 빠진다. 0.5초 간격 3회 재시도 후에도 실패하면 <topic>.DLT 로 옮겨
 *       원본(헤더에 예외·원래 파티션/오프셋 포함)을 보존한다. 대사에서는 MISSING_IN_SETTLEMENT 로 드러난다.
 */
@Configuration
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class KafkaTopicConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaTopicConfig.class);

    @Bean
    NewTopic transactionsTopic(@Value("${app.kafka.topic:ai-finance.transactions}") String topic,
                               @Value("${app.kafka.partitions:3}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }

    /** DLT 는 원본과 파티션 수를 맞춘다 (DeadLetterPublishingRecoverer 가 원래 파티션 번호로 보냄) */
    @Bean
    NewTopic transactionsDeadLetterTopic(@Value("${app.kafka.topic:ai-finance.transactions}") String topic,
                                         @Value("${app.kafka.partitions:3}") int partitions) {
        return TopicBuilder.name(topic + ".DLT").partitions(partitions).replicas(1).build();
    }

    /** Spring Boot 가 이 빈을 @KafkaListener 컨테이너 팩토리에 자동으로 연결한다 */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        // 목적지를 명시한다: 기본 이름 규칙은 Spring Kafka 버전마다 다르다(이 버전은 "-dlt").
        // 기본값에 맡기면 미리 만든 DLT 가 아니라 브로커가 자동 생성한 다른 토픽(파티션 1개)으로 간다 — CI 통합 테스트로 확인.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> {
            log.error("[외부 정산] 처리 실패 메시지를 DLT 로 이동 (partition {}, offset {}): {}",
                    record.partition(), record.offset(), ex.getMessage());
            recoverer.accept(record, ex);
        }, new FixedBackOff(500L, 3L));
        // 메시지 형식 자체가 깨진 경우는 재시도해도 소용없으므로 바로 DLT 로
        handler.addNotRetryableExceptions(IllegalArgumentException.class, tools.jackson.core.JacksonException.class);
        return handler;
    }
}
