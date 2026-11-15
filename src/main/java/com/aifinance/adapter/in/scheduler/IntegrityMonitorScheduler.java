package com.aifinance.adapter.in.scheduler;

import com.aifinance.application.service.IntegrityMonitor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 화면을 보는 사람이 없어도 원장 불일치를 감지하도록 주기적으로 검증한다 (불일치 시 ERROR 로그·지표) */
@Component
@ConditionalOnProperty(name = "app.integrity.monitor.enabled", havingValue = "true", matchIfMissing = true)
public class IntegrityMonitorScheduler {

    private final IntegrityMonitor monitor;
    private final Duration interval;

    public IntegrityMonitorScheduler(IntegrityMonitor monitor,
                                     @Value("${app.integrity.check-interval-ms:5000}") long intervalMs) {
        this.monitor = monitor;
        this.interval = Duration.ofMillis(intervalMs);
    }

    /** 1초마다 깨어나 "주기가 됐는지"만 본다 — 실제 검증 간격은 max(설정 간격, 직전 소요 × 10) */
    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    public void check() {
        monitor.refreshIfDue(interval);
    }
}
