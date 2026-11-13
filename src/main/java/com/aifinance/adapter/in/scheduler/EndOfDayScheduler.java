package com.aifinance.adapter.in.scheduler;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.application.service.OutboxRelayService;
import com.aifinance.application.usecase.ReconciliationUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 일마감(EOD) 배치.
 *  1) 마감 전 Outbox 를 비워 전날 거래가 모두 정산 시스템에 도착하도록 한다
 *  2) 전날 영업일 [00:00, 24:00) 대사 실행 → 결과를 reconciliation_runs 에 기록
 *
 * 매일 00:10 실행 (자정 직전 거래의 전달 지연을 흡수하기 위해 10분 유예).
 */
@Component
@ConditionalOnProperty(name = "app.eod.enabled", havingValue = "true", matchIfMissing = true)
public class EndOfDayScheduler {

    private static final Logger log = LoggerFactory.getLogger(EndOfDayScheduler.class);

    private final OutboxRelayService relayService;
    private final ReconciliationUseCase reconciliationUseCase;

    public EndOfDayScheduler(OutboxRelayService relayService, ReconciliationUseCase reconciliationUseCase) {
        this.relayService = relayService;
        this.reconciliationUseCase = reconciliationUseCase;
    }

    @Scheduled(cron = "${app.eod.cron:0 10 0 * * *}", zone = AiFinanceWorldApplication.BUSINESS_ZONE)
    public void closeBusinessDay() {
        // cron 과 같은 시간대로 '어제'를 계산 (JVM 기본 시간대 = BUSINESS_ZONE)
        LocalDate businessDate = LocalDate.now(ZoneId.of(AiFinanceWorldApplication.BUSINESS_ZONE)).minusDays(1);
        int drained = relayService.drain(50);
        var result = reconciliationUseCase.reconcileDay(businessDate);
        log.info("[EOD] {} 마감 완료 — 마감 전 발행 {}건, 대사 결과 {}", businessDate, drained, result.status());
    }
}
