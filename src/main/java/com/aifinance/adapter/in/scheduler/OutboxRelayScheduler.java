package com.aifinance.adapter.in.scheduler;

import com.aifinance.application.service.OutboxRelayService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 폴링 릴레이 트리거.
 * 테스트에서는 app.outbox.relay.auto=false 로 끄고 relayOnce() 를 직접 호출해 결정적으로 검증한다.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.relay.auto", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {

    private final OutboxRelayService relayService;

    public OutboxRelayScheduler(OutboxRelayService relayService) {
        this.relayService = relayService;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay.interval-ms:100}")
    public void relay() {
        relayService.relayOnce();
    }
}
