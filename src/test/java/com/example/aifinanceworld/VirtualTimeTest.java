package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.adapter.in.scheduler.SimulationEngine.State;
import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.port.out.FaultInjectionPort;
import com.aifinance.application.service.OutboxRelayService;
import com.aifinance.application.service.VirtualClock;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.ReconciliationUseCase;
import com.aifinance.application.usecase.ReconciliationUseCase.RunView;
import com.aifinance.application.usecase.TransferUseCase;
import com.aifinance.domain.account.AccountType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 가상 시간(VirtualClock) — 144틱 = 가상 하루, 가상 자정(+10분 유예)에 SimulationEngine 이 자동으로
 * 일마감 대사를 1회 실행하는지, 브로커 장애 중에도 오탐 없이 PENDING_IN_FLIGHT 로 잡히는지 확인한다.
 *
 * agentCount=1 로 월드를 만들어 SimulationEngine.runTick() 이 매번 "agents.size() &lt; 2" 로 조기 반환하게 해서
 * (가상 자정 판정은 그 앞에서 이미 실행됨) 에이전트의 확률적 행동 없이 가상 시계만 결정적으로 진행시킨다.
 */
@SpringBootTest(classes = AiFinanceWorldApplication.class, properties = "app.simulation.bootstrap=false")
class VirtualTimeTest {

    @Autowired SimulationEngine engine;
    @Autowired VirtualClock virtualClock;
    @Autowired AgentUseCase agentUseCase;
    @Autowired AccountPort accountPort;
    @Autowired TransferUseCase transferUseCase;
    @Autowired OutboxRelayService relay;
    @Autowired ReconciliationUseCase reconciliation;
    @Autowired FaultInjectionPort faults;
    @Autowired JdbcTemplate jdbc;

    Long agentAccountId;
    Long merchantAccountId;

    @BeforeEach
    void setUp() {
        if (engine.getState() == State.RUNNING || engine.getState() == State.PAUSED) engine.stop();
        if (engine.getState() == State.ENDED) engine.newSimulation();
        if (engine.getState() == State.SETUP) engine.setup(1);

        faults.setBrokerDown(false);
        faults.loseNextAcks(0);
        jdbc.update("update outbox_events set next_attempt_at = created_at where status = 'PENDING'"); // 같은 마이크로초 경합 방지
        relay.drain(100); // 월드 생성 시의 초기 자본금(OPENING) 이벤트는 장애 주입 전에 비워 둔다

        agentAccountId = agentUseCase.findAll().get(0).getAccount().getId();
        merchantAccountId = accountPort.findByType(AccountType.MERCHANT).get(0).getId();
    }

    /** 144틱마다 가상 하루가 지나고, 가상 자정(+10분)을 지나는 틱에서 그 하루치 대사가 "정확히 한 번" 자동 실행된다 */
    @Test
    void 가상_자정에_일마감_대사가_정확히_한번_자동_실행된다() {
        transferUseCase.transfer(agentAccountId, merchantAccountId, BigDecimal.valueOf(1_000), "day0 거래");
        relay.drain(100);

        for (int i = 0; i < 150; i++) {
            engine.runTick();
        }

        assertEquals(1, engine.getVirtualDaysElapsed(), "day0 하루치 대사가 한 번 돌아야 한다");
        assertEquals(1, reconciliation.recent().size());
        RunView run = reconciliation.latest();
        assertEquals(virtualClock.startDate(), run.businessDate());
        assertEquals("MATCHED", run.status());

        // 같은 가상 하루(day1) 안에서는 더 틱을 돌려도 중복 실행되지 않는다
        for (int i = 0; i < 10; i++) {
            engine.runTick();
        }
        assertEquals(1, engine.getVirtualDaysElapsed());
        assertEquals(1, reconciliation.recent().size());
    }

    /** 브로커 중단 중에 가상 자정이 지나도 오탐(누락) 없이 PENDING_IN_FLIGHT 로 잡히고, 복구 후 재대사하면 MATCHED */
    @Test
    void 브로커_중단_중_가상_자정이_지나면_PENDING_IN_FLIGHT_복구_후_MATCHED() {
        faults.setBrokerDown(true);
        transferUseCase.transfer(agentAccountId, merchantAccountId, BigDecimal.valueOf(1_000), "day0 거래 (브로커 중단)");
        long pendingEvents = jdbc.queryForObject("select count(*) from outbox_events where status = 'PENDING'", Long.class);
        assertTrue(pendingEvents >= 1);

        for (int i = 0; i < 150; i++) {
            engine.runTick();
        }

        assertEquals(1, engine.getVirtualDaysElapsed(), "대사 자체는(판정이 무엇이든) 그 하루에 한 번 돈다");
        RunView downRun = reconciliation.latest();
        assertEquals(virtualClock.startDate(), downRun.businessDate());
        assertEquals("PENDING_IN_FLIGHT", downRun.status());
        assertEquals(pendingEvents, downRun.inFlightCount(), "장애 중 Outbox 에 대기 중인 건수만큼이 전부 전달 중으로 분류된다");
        assertEquals(0, downRun.missingCount(), "Outbox 에 남아 있는 건은 유실(누락)이 아니다");

        faults.setBrokerDown(false);
        jdbc.update("update outbox_events set next_attempt_at = created_at where status = 'PENDING'"); // 백오프 대기 해제
        relay.drain(100);
        RunView recovered = reconciliation.reconcileDay(virtualClock.startDate());
        assertEquals("MATCHED", recovered.status());
        assertEquals(0, recovered.inFlightCount());
    }
}
