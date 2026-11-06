package com.aifinance.adapter.out.messaging;

import com.aifinance.application.event.TransactionPostedEvent;
import com.aifinance.application.port.out.TransactionEventPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** Kafka 를 쓰지 않을 때(app.kafka.enabled=false, 기본값)의 대체 구현 - 디버그 로그만 남긴다. */
@Component
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "false", matchIfMissing = true)
public class LoggingTransactionEventAdapter implements TransactionEventPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingTransactionEventAdapter.class);
    private final AtomicLong published = new AtomicLong();

    @Override
    public void publish(TransactionPostedEvent event) {
        published.incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("transaction event: {}", event.toJson());
        }
    }

    @Override
    public long publishedCount() {
        return published.get();
    }
}
