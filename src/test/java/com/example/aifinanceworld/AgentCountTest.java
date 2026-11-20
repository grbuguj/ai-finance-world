package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.application.port.out.AgentPort;
import com.aifinance.application.usecase.IntegrityUseCase;
import com.aifinance.application.usecase.IntegrityUseCase.IntegrityReport;
import com.aifinance.application.usecase.SimulationSetupUseCase;
import com.aifinance.domain.agent.Agent;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 에이전트 수 설정(app.simulation.agent-count / POST setup). 에이전트가 늘어도 같은 무결성 조건이 성립해야 하고,
 * 같은 설정이면 항상 같은 에이전트(이름·확률)가 만들어져야 한다 (재현성).
 */
class AgentCountTest {

    private static void assertWorldIntegrity(AgentPort agentPort, SimulationEngine engine,
                                             IntegrityUseCase integrityUseCase, int expectedAgentCount) {
        assertEquals(expectedAgentCount, agentPort.count());

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

    @Nested
    @SpringBootTest(classes = AiFinanceWorldApplication.class, properties = "app.simulation.agent-count=2")
    class Agents2 {
        @Autowired AgentPort agentPort;
        @Autowired SimulationEngine engine;
        @Autowired IntegrityUseCase integrityUseCase;

        @Test
        void 설정한_수만큼_에이전트가_만들어지고_무결성이_성립한다() {
            assertWorldIntegrity(agentPort, engine, integrityUseCase, 2);
        }
    }

    @Nested
    @SpringBootTest(classes = AiFinanceWorldApplication.class, properties = "app.simulation.agent-count=10")
    class Agents10 {
        @Autowired AgentPort agentPort;
        @Autowired SimulationEngine engine;
        @Autowired IntegrityUseCase integrityUseCase;

        @Test
        void 설정한_수만큼_에이전트가_만들어지고_무결성이_성립한다() {
            assertWorldIntegrity(agentPort, engine, integrityUseCase, 10);
        }
    }

    @Nested
    @SpringBootTest(classes = AiFinanceWorldApplication.class, properties = "app.simulation.agent-count=50")
    class Agents50 {
        @Autowired AgentPort agentPort;
        @Autowired SimulationEngine engine;
        @Autowired IntegrityUseCase integrityUseCase;

        @Test
        void 설정한_수만큼_에이전트가_만들어지고_무결성이_성립한다() {
            assertWorldIntegrity(agentPort, engine, integrityUseCase, 50);
        }
    }

    @Nested
    @SpringBootTest(classes = AiFinanceWorldApplication.class, properties = "app.simulation.agent-count=200")
    class Agents200 {
        @Autowired AgentPort agentPort;
        @Autowired SimulationEngine engine;
        @Autowired IntegrityUseCase integrityUseCase;

        @Test
        void 설정한_수만큼_에이전트가_만들어지고_무결성이_성립한다() {
            assertWorldIntegrity(agentPort, engine, integrityUseCase, 200);
        }
    }

    /** 같은 설정(agentCount)이면 항상 같은 이름·확률의 에이전트가 만들어진다 (번호 시드 재현성) */
    @Nested
    @SpringBootTest(classes = AiFinanceWorldApplication.class, properties = "app.simulation.agent-count=30")
    class Reproducibility {
        @Autowired SimulationSetupUseCase setupUseCase;

        @Test
        void personaOf_는_같은_번호에_항상_같은_페르소나를_반환한다() {
            for (int i = 0; i < 30; i++) {
                var a = SimulationSetupUseCase.personaOf(i);
                var b = SimulationSetupUseCase.personaOf(i);
                assertEquals(a.name(), b.name());
                assertEquals(a.initial(), b.initial());
                assertEquals(a.spend(), b.spend());
                assertEquals(a.pay(), b.pay());
                assertEquals(a.invest(), b.invest());
                assertEquals(a.refund(), b.refund());
            }
        }

        @Test
        void 실제로_생성된_에이전트_이름이_personaOf_와_일치한다(@Autowired AgentPort agentPort) {
            List<String> expected = java.util.stream.IntStream.range(0, 30)
                    .mapToObj(i -> SimulationSetupUseCase.personaOf(i).name())
                    .collect(Collectors.toList());
            List<String> actual = agentPort.findAll().stream().map(Agent::getName).collect(Collectors.toList());
            assertEquals(expected, actual);
        }
    }
}
