package com.aifinance.adapter.in.scheduler;

import com.aifinance.application.port.out.IdempotencyPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 보관 기간(기본 24시간)이 지난 멱등성 키 정리. 실무에서도 키는 일정 기간만 보관한다. */
@Component
public class IdempotencyKeyCleaner {

    private final IdempotencyPort idempotencyPort;
    private final long retentionHours;

    public IdempotencyKeyCleaner(IdempotencyPort idempotencyPort,
                                 @Value("${app.idempotency.retention-hours:24}") long retentionHours) {
        this.idempotencyPort = idempotencyPort;
        this.retentionHours = retentionHours;
    }

    @Scheduled(fixedDelayString = "${app.idempotency.cleanup-interval-ms:600000}")
    public void cleanup() {
        idempotencyPort.deleteOlderThan(LocalDateTime.now().minusHours(retentionHours));
    }
}
