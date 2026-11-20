package com.aifinance.adapter.in.init;

import com.aifinance.application.usecase.SimulationSetupUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * app.simulation.bootstrap=true 일 때만 기동 시 월드(시스템 계좌 + 에이전트)를 만든다.
 * 테스트/부하테스트 프로필은 true (기존처럼 기동 즉시 월드가 있다는 전제 유지),
 * 운영/대시보드 기본값은 false — 사용자가 POST /simulation/setup 으로 에이전트 수를 고른 뒤 월드가 만들어진다.
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private final SimulationSetupUseCase setupUseCase;
    private final boolean bootstrap;
    private final int agentCount;

    public DataInitializer(SimulationSetupUseCase setupUseCase,
                           @Value("${app.simulation.bootstrap:false}") boolean bootstrap,
                           @Value("${app.simulation.agent-count:10}") int agentCount) {
        this.setupUseCase = setupUseCase;
        this.bootstrap = bootstrap;
        this.agentCount = agentCount;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (bootstrap) {
            setupUseCase.initialize(agentCount);
        }
    }
}
