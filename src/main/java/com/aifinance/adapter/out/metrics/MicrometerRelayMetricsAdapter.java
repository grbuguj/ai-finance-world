package com.aifinance.adapter.out.metrics;

import com.aifinance.application.port.out.OutboxPort;
import com.aifinance.application.port.out.RelayMetricsPort;
import com.aifinance.domain.outbox.OutboxStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Outbox 지표 (Prometheus)
 *  - afw_outbox_publish_lag_seconds       : 거래 커밋 → 브로커 ack 까지 지연 (히스토그램)
 *  - afw_outbox_publish_failed_total{reason}
 *  - afw_outbox_dead_total                : DEAD 격리 건수 (알람 대상)
 *  - afw_outbox_pending                   : 현재 발행 대기 건수 (브로커 장애 시 증가 → 복구 후 0 으로 수렴)
 */
@Component
public class MicrometerRelayMetricsAdapter implements RelayMetricsPort {

    private final Timer lag;
    private final Counter dead;
    private final MeterRegistry registry;

    public MicrometerRelayMetricsAdapter(MeterRegistry registry, OutboxPort outboxPort) {
        this.registry = registry;
        this.lag = Timer.builder("afw.outbox.publish.lag")
                .description("거래 커밋부터 브로커 ack 까지 지연")
                .publishPercentileHistogram()
                .register(registry);
        this.dead = Counter.builder("afw.outbox.dead")
                .description("최대 재시도 초과로 격리된 이벤트")
                .register(registry);
        Gauge.builder("afw.outbox.pending", outboxPort, p -> p.countByStatus(OutboxStatus.PENDING))
                .description("발행 대기 중인 Outbox 이벤트 수")
                .register(registry);
    }

    @Override
    public void recordPublished(long lagMillis) {
        lag.record(Math.max(0, lagMillis), TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordPublishFailed(String reason) {
        Counter.builder("afw.outbox.publish.failed")
                .description("Outbox 발행 실패 (재시도 예정)")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    @Override
    public void recordDead() {
        dead.increment();
    }
}
