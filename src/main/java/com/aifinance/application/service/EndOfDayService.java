package com.aifinance.application.service;

import com.aifinance.application.usecase.ReconciliationUseCase;
import com.aifinance.application.usecase.ReconciliationUseCase.RunView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 일마감(EOD) 처리 본체 — 실제 시계 기반 스케줄러(EndOfDayScheduler)와
 * 가상 시계 기반 자동 트리거(SimulationEngine)가 공통으로 사용한다.
 *
 *  1) 마감 전 Outbox 를 비워 그 영업일 거래가 모두 정산 시스템에 도착하도록 한다
 *  2) 해당 영업일 [00:00, 24:00) 대사 실행 → 결과를 reconciliation_runs 에 기록
 */
@Service
public class EndOfDayService {

    private static final Logger log = LoggerFactory.getLogger(EndOfDayService.class);

    private final OutboxRelayService relayService;
    private final ReconciliationUseCase reconciliationUseCase;

    public EndOfDayService(OutboxRelayService relayService, ReconciliationUseCase reconciliationUseCase) {
        this.relayService = relayService;
        this.reconciliationUseCase = reconciliationUseCase;
    }

    public RunView closeBusinessDay(LocalDate businessDate) {
        int drained = relayService.drain(50);
        RunView result = reconciliationUseCase.reconcileDay(businessDate);
        log.info("[EOD] {} 마감 완료 — 마감 전 발행 {}건, 대사 결과 {}", businessDate, drained, result.status());
        return result;
    }
}
