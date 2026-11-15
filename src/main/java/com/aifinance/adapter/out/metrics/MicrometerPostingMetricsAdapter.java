package com.aifinance.adapter.out.metrics;

import com.aifinance.application.port.out.PostingMetricsPort;
import com.aifinance.domain.transfer.TransactionType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Prometheus 로 노출되는 지표
 *  - afw_posting_duration_seconds{type}       : 기장 1건 소요시간 (히스토그램 → p50/p99)
 *  - afw_posting_lock_wait_seconds{type}      : 계좌 행 락 대기시간 (핫스팟 병목 진단용)
 *  - afw_posting_failed_total{type,reason}    : 실패 건수 (잔액 부족, 데드락, 락 타임아웃 ...)
 */
@Component
public class MicrometerPostingMetricsAdapter implements PostingMetricsPort {

    private final MeterRegistry registry;

    public MicrometerPostingMetricsAdapter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordPosted(TransactionType type, long totalNanos, long lockWaitNanos) {
        Timer.builder("afw.posting.duration")
                .description("거래 기장 소요시간")
                .tag("type", type.name())
                .publishPercentileHistogram()
                .register(registry)
                .record(totalNanos, TimeUnit.NANOSECONDS);
        Timer.builder("afw.posting.lock.wait")
                .description("계좌 행 락 대기시간")
                .tag("type", type.name())
                .publishPercentileHistogram()
                .register(registry)
                .record(lockWaitNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordFailed(TransactionType type, String reason) {
        Counter.builder("afw.posting.failed")
                .description("거래 기장 실패 건수")
                .tag("type", type.name())
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
