package com.aifinance.adapter.out.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.core.JacksonException;

/**
 * 정산 수신(Kafka 소비자) 오류 처리 — DB 가 잠깐 죽어도 레코드를 버리지 않는다.
 *
 * 기본 오류 처리기(DefaultErrorHandler)는 재시도 9회 후 그 레코드를 "건너뛴다". DB 장애 실험(db-fault)에서 MySQL 을 20초 멈췄다 켰을 때
 * 장애 중에 소비된 이벤트 2건이 재시도 소진으로 건너뛰어져, Outbox 에서는 PUBLISHED 인데 정산 장부에는 없는 "진짜 유실"이 생겼다
 * (대사가 UNMATCHED 로 잡음). 그래서 일시적 오류(DB 연결 불가·락 대기 등)는 시간 제한 없이 지수 백오프로 재시도한다 —
 * 그동안 해당 파티션은 멈추지만 순서가 보존되고, DB 가 돌아오면 이어서 처리한다 (SettlementInbox 가 멱등이라 재처리해도 안전).
 * 반면 고쳐질 수 없는 메시지(JSON 깨짐, 필수값 누락)는 재시도해도 같으므로 즉시 건너뛰어 파티션을 막지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class SettlementConsumerErrorConfig {

    private static final Logger log = LoggerFactory.getLogger(SettlementConsumerErrorConfig.class);

    /** 0.5s, 1s, 2s, 4s, 그 뒤 5s 간격으로 무제한 재시도 */
    static BackOff settlementBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxInterval(5_000);
        return backOff; // maxElapsedTime 기본값 = Long.MAX_VALUE (무제한)
    }

    @Bean
    public DefaultErrorHandler settlementErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, e) -> log.error("[정산 수신] 재시도 불가 메시지를 건너뜀 (offset {}): {}", record.offset(), e.toString()),
                settlementBackOff());
        handler.addNotRetryableExceptions(IllegalArgumentException.class, JacksonException.class);
        return handler;
    }
}
