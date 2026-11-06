package com.aifinance.adapter.out.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 외부 시스템(정산/알림/회계) 연동 시뮬레이션.
 * 은행 코어와 분리된 소비자가 거래 이벤트를 구독해 처리한다.
 */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class ExternalSettlementConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExternalSettlementConsumer.class);
    private final AtomicLong consumed = new AtomicLong();

    @KafkaListener(topics = "${app.kafka.topic:ai-finance.transactions}", groupId = "external-settlement")
    public void onTransaction(String message) {
        long n = consumed.incrementAndGet();
        if (n % 100 == 1) {
            log.info("[외부 정산 시스템] 누적 {}건 수신, 최근: {}", n, message);
        }
    }

    public long consumedCount() {
        return consumed.get();
    }
}
