package com.aifinance.adapter.out.metrics;

import com.aifinance.application.port.out.IntegrityMetricsPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *  - afw_integrity_check_seconds : 무결성 검증 1회 소요 시간 (원장 크기에 비례 → 증가 추세 감시)
 *  - afw_integrity_balanced      : 마지막 검증 결과 (1 = 균형, 0 = 불일치 → 알람)
 */
@Component
public class MicrometerIntegrityMetricsAdapter implements IntegrityMetricsPort {

    private final Timer checkTimer;
    private final AtomicInteger balanced = new AtomicInteger(1);

    public MicrometerIntegrityMetricsAdapter(MeterRegistry registry) {
        this.checkTimer = Timer.builder("afw.integrity.check")
                .description("원장 무결성 검증 소요 시간")
                .register(registry);
        Gauge.builder("afw.integrity.balanced", balanced, AtomicInteger::get)
                .description("마지막 무결성 검증 결과 (1=균형)")
                .register(registry);
    }

    @Override
    public void recordCheck(long durationMillis, boolean isBalanced) {
        checkTimer.record(durationMillis, TimeUnit.MILLISECONDS);
        balanced.set(isBalanced ? 1 : 0);
    }
}
