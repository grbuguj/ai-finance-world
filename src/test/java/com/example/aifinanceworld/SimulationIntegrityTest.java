package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.application.port.out.TransferPort;
import com.aifinance.application.usecase.IntegrityUseCase;
import com.aifinance.application.usecase.IntegrityUseCase.IntegrityReport;
import com.aifinance.domain.transfer.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 장시간 시뮬레이션 후 원장 무결성 검증.
 * 에이전트 10명이 소비/송금/투자/환불/급여를 수백 틱 동안 수행한 뒤에도
 * ∑DEBIT = ∑CREDIT, 계좌별 잔액 = 원장 재계산 값, 전체 잔액 합 = 0 이 성립해야 한다.
 */
@SpringBootTest(classes = AiFinanceWorldApplication.class)
class SimulationIntegrityTest {

    private static final int TICKS = 300;

    @Autowired SimulationEngine engine;
    @Autowired IntegrityUseCase integrityUseCase;
    @Autowired TransferPort transferPort;

    @Test
    void 장시간_시뮬레이션_후_복식부기_무결성() {
        engine.reset();
        for (int i = 0; i < TICKS; i++) {
            engine.runTick();
        }

        Map<TransactionType, Long> byType = transferPort.countByType();
        System.out.println("[SimulationIntegrityTest] " + TICKS + "틱 거래 유형별 건수: " + byType);

        IntegrityReport report = integrityUseCase.check();
        System.out.println("[SimulationIntegrityTest] DEBIT=" + report.totalDebit()
                + " CREDIT=" + report.totalCredit()
                + " 불일치계좌=" + report.mismatchedAccounts().size()
                + " 전체잔액합=" + report.sumOfAllBalances());

        // 모든 유형의 거래가 실제로 발생했는지
        for (TransactionType type : new TransactionType[]{
                TransactionType.TRANSFER, TransactionType.SPEND, TransactionType.REFUND,
                TransactionType.INVEST, TransactionType.INVEST_RETURN, TransactionType.SALARY}) {
            assertTrue(byType.getOrDefault(type, 0L) > 0, type + " 거래가 발생해야 함");
        }

        assertEquals(0, report.totalDebit().compareTo(report.totalCredit()), "∑DEBIT = ∑CREDIT");
        assertEquals(0, report.transfersWithoutTwoEntries(), "모든 거래는 분개 2건");
        assertEquals(0, report.mismatchedAccountCount(), "계좌 잔액 = 원장 재계산 값: " + report.mismatchedAccounts());
        assertEquals(0, report.sumOfAllBalances().signum(), "전체 잔액 합 = 0 (돈이 새거나 생기지 않음)");
        assertTrue(report.balanced());
    }
}
