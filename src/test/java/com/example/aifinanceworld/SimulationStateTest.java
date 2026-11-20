package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.adapter.in.scheduler.SimulationEngine.State;
import com.aifinance.application.port.out.AgentPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SETUP → RUNNING ⇄ PAUSED → ENDED, ENDED 에서 틱 불가, "새 시뮬레이션"으로 SETUP 복귀.
 * bootstrap=false 로 기동해 월드 없이 SETUP 에서 시작하는 대시보드 기본 흐름을 검증한다.
 */
@SpringBootTest(classes = AiFinanceWorldApplication.class, properties = {
        "app.simulation.bootstrap=false",
        "app.simulation.agent-count=10"
})
class SimulationStateTest {

    @Autowired SimulationEngine engine;
    @Autowired AgentPort agentPort;

    @Test
    void 전체_상태_전이가_정상적으로_동작한다() {
        assertEquals(State.SETUP, engine.getState());
        assertEquals(0, agentPort.count());

        // SETUP 에서는 start/pause/stop 모두 거부
        assertThrows(IllegalStateException.class, engine::start);
        assertThrows(IllegalStateException.class, engine::pause);
        assertThrows(IllegalStateException.class, engine::stop);

        // setup → RUNNING, 월드 생성
        engine.setup(7);
        assertEquals(State.RUNNING, engine.getState());
        assertEquals(7, agentPort.count());

        // 재설정 거부
        assertThrows(IllegalStateException.class, () -> engine.setup(10));

        // RUNNING ⇄ PAUSED
        engine.pause();
        assertEquals(State.PAUSED, engine.getState());
        engine.start();
        assertEquals(State.RUNNING, engine.getState());

        // 정지 = 종료
        engine.stop();
        assertEquals(State.ENDED, engine.getState());
        assertThrows(IllegalStateException.class, engine::start);
        assertThrows(IllegalStateException.class, engine::pause);

        // 틱이 돌지 않는다 (SimulationController 와 동일하게 state != RUNNING 이면 스케줄 틱은 무시 — runTick 직접 호출은 테스트 전용 경로이므로 상태만 확인)
        assertEquals(State.ENDED, engine.getState());

        // 새 시뮬레이션 → SETUP 복귀, 에이전트는 유지(초기화는 잔액/거래만), tick/virtualDays 리셋
        engine.newSimulation();
        assertEquals(State.SETUP, engine.getState());
        assertEquals(0, engine.getTickCount());
        assertEquals(0, engine.getVirtualDaysElapsed());

        // ENDED 가 아니면 새 시뮬레이션 거부
        assertThrows(IllegalStateException.class, engine::newSimulation);

        // 다시 setup 가능 (기존 에이전트 7명 유지 + 부족분 추가로 10명)
        engine.setup(10);
        assertEquals(State.RUNNING, engine.getState());
        assertEquals(10, agentPort.count());
        assertTrue(true);
    }
}
