package com.aifinance.adapter.in.scheduler;

import com.aifinance.application.service.IntegrityMonitor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 화면을 보는 사람이 없어도 원장 불일치를 감지하도록 주기적으로 검증한다 (불일치 시 ERROR 로그·지표) */
@Component
@ConditionalOnProperty(name = "app.integrity.monitor.enabled", havingValue = "true", matchIfMissing = true)
public class IntegrityMonitorScheduler {

    private final IntegrityMonitor monitor;

    public IntegrityMonitorScheduler(IntegrityMonitor monitor) {
        this.monitor = monitor;
    }

    @Scheduled(fixedDelayString = "${app.integrity.check-interval-ms:5000}", initialDelay = 5000)
    public void check() {
        monitor.refresh();
    }
}
