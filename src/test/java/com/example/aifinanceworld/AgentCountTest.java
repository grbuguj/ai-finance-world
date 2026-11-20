package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.application.port.out.AgentPort;
import com.aifinance.application.usecase.IntegrityUseCase;
import com.aifinance.application.usecase.IntegrityUseCase.IntegrityReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 에이전트 수 설정(app.simulation.agent-count). 에이전트가 늘어도 같은 무결성 조건이 성립해야 한다.
 */
@SpringBootTest(classes = AiFinanceWorldApplication.class, properties = "app.simulation.agent-count=50")
class AgentCountTest {

    @Autowired AgentPort agentPort;
    @Autowired SimulationEngine engine;
    @Autowired IntegrityUseCase integrityUseCase;

    @Test
    void 설정한_수만큼_에이전트가_만들어지고_무결성이_성립한다() {
        assertEquals(50, agentPort.count());

        engine.reset();
        for (int i = 0; i < 200; i++) {
            engine.runTick();
        }

        IntegrityReport report = integrityUseCase.check();
        assertEquals(0, report.totalDebit().compareTo(report.totalCredit()), "∑DEBIT = ∑CREDIT");
        assertEquals(0, report.transfersWithoutTwoEntries());
        assertEquals(0, report.mismatchedAccountCount(), "잔액 = 원장 재계산 값: " + report.mismatchedAccounts());
        assertEquals(0, report.sumOfAllBalances().signum(), "전체 잔액 합 = 0");
        assertTrue(report.balanced());
    }
}
