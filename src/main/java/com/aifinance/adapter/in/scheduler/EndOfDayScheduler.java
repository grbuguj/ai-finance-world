package com.aifinance.adapter.in.scheduler;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.application.service.EndOfDayService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 실제 시계 기반 일마감(EOD) 배치 — 매일 00:10 실행(자정 직전 거래의 전달 지연을 흡수하기 위해 10분 유예).
 *
 * 시뮬레이션은 가상 시계를 쓰므로 이 스케줄러는 쓰지 않는다 (기본 app.eod.enabled=false).
 * 실제 시계로 운영하는 별도 배포가 있다면 true 로 켜서 그대로 쓸 수 있다.
 * 실제 처리(Outbox 비우기 + 대사 실행)는 EndOfDayService 가 한다 — SimulationEngine 의 가상 자정 트리거와 공유.
 */
@Component
@ConditionalOnProperty(name = "app.eod.enabled", havingValue = "true", matchIfMissing = true)
public class EndOfDayScheduler {

    private final EndOfDayService endOfDayService;

    public EndOfDayScheduler(EndOfDayService endOfDayService) {
        this.endOfDayService = endOfDayService;
    }

    @Scheduled(cron = "${app.eod.cron:0 10 0 * * *}", zone = AiFinanceWorldApplication.BUSINESS_ZONE)
    public void closeBusinessDay() {
        // cron 과 같은 시간대로 '어제'를 계산 (JVM 기본 시간대 = BUSINESS_ZONE)
        LocalDate businessDate = LocalDate.now(ZoneId.of(AiFinanceWorldApplication.BUSINESS_ZONE)).minusDays(1);
        endOfDayService.closeBusinessDay(businessDate);
    }
}
