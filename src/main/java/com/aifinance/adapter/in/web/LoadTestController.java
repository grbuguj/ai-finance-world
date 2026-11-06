package com.aifinance.adapter.in.web;

import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.usecase.SimulationSetupUseCase;
import com.aifinance.domain.account.AccountType;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 부하 테스트(k6) 준비용 API. loadtest 프로필에서만 활성화된다.
 *   --spring.profiles.active=loadtest            (H2)
 *   --spring.profiles.active=mysql,loadtest      (MySQL)
 */
@RestController
@Profile("loadtest")
@RequestMapping("/loadtest")
public class LoadTestController {

    private final SimulationSetupUseCase setupUseCase;
    private final SimulationEngine simulationEngine;
    private final AccountPort accountPort;

    public LoadTestController(SimulationSetupUseCase setupUseCase, SimulationEngine simulationEngine,
                              AccountPort accountPort) {
        this.setupUseCase = setupUseCase;
        this.simulationEngine = simulationEngine;
        this.accountPort = accountPort;
    }

    /** 시뮬레이션을 멈추고(측정 오염 방지) 부하 테스트용 계좌를 만든다 */
    @PostMapping("/setup")
    public Map<String, Object> setup(@RequestParam(defaultValue = "100") int accounts,
                                     @RequestParam(defaultValue = "100000000") BigDecimal balance,
                                     @RequestParam(defaultValue = "LT") String prefix) {
        simulationEngine.stop();
        List<Long> ids = setupUseCase.createFundedAccounts(prefix, Math.min(accounts, 10_000), balance);
        List<Long> merchants = accountPort.findByType(AccountType.MERCHANT).stream().map(a -> a.getId()).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountIds", ids);
        result.put("merchantIds", merchants);
        return result;
    }

    @GetMapping("/merchants")
    public List<Long> merchants() {
        return accountPort.findByType(AccountType.MERCHANT).stream().map(a -> a.getId()).toList();
    }
}
