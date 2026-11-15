package com.aifinance.adapter.in.init;

import com.aifinance.application.usecase.SimulationSetupUseCase;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 애플리케이션 기동 시 시뮬레이션 세계(시스템 계좌 + 에이전트 10명)를 만든다.
 * 에이전트 페르소나와 확률은 SimulationSetupUseCase 에 정의되어 있다.
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private final SimulationSetupUseCase setupUseCase;

    public DataInitializer(SimulationSetupUseCase setupUseCase) {
        this.setupUseCase = setupUseCase;
    }

    @Override
    public void run(ApplicationArguments args) {
        setupUseCase.initialize();
    }
}
