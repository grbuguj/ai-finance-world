package com.aifinance.application.usecase;

import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.port.out.AgentPort;
import com.aifinance.application.service.AccountLockExecutor;
import com.aifinance.application.service.LedgerPostingService;
import com.aifinance.domain.account.Account;
import com.aifinance.domain.account.AccountType;
import com.aifinance.domain.agent.Agent;
import com.aifinance.domain.transfer.TransactionType;
import com.aifinance.domain.transfer.Transfer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 에이전트 조회 + 급여 지급.
 * 급여는 발행(CAPITAL) 계정 → 에이전트 계좌 거래로 기록되어, 돈이 "갑자기 생기는" 일 없이 원장에 남는다.
 */
@Service
public class AgentUseCase {

    private final AgentPort agentPort;
    private final AccountPort accountPort;
    private final AccountLockExecutor lockExecutor;
    private final LedgerPostingService postingService;
    private final BigDecimal salaryRate;

    public AgentUseCase(AgentPort agentPort, AccountPort accountPort,
                        AccountLockExecutor lockExecutor, LedgerPostingService postingService,
                        @Value("${app.simulation.salary-rate:0.05}") double salaryRate) {
        this.agentPort = agentPort;
        this.accountPort = accountPort;
        this.lockExecutor = lockExecutor;
        this.postingService = postingService;
        this.salaryRate = BigDecimal.valueOf(salaryRate);
    }

    public List<Agent> findAll() {
        return agentPort.findAll();
    }

    /** 모든 에이전트에게 급여(초기 잔액 × salaryRate) 지급 */
    public List<Transfer> paySalary() {
        Long capitalId = capitalAccount().getId();
        List<Transfer> paid = new ArrayList<>();
        for (Agent agent : agentPort.findAll()) {
            BigDecimal amount = BigDecimal.valueOf(agent.getSalary()).multiply(salaryRate).setScale(0, RoundingMode.DOWN);
            if (amount.signum() <= 0) continue;
            Long agentAccountId = agent.getAccount().getId();
            paid.add(lockExecutor.execute(List.of(capitalId, agentAccountId), () ->
                    postingService.post(capitalId, agentAccountId, amount, TransactionType.SALARY, "월급날", null)));
        }
        return paid;
    }

    private Account capitalAccount() {
        List<Account> capitals = accountPort.findByType(AccountType.CAPITAL);
        if (capitals.isEmpty()) {
            throw new IllegalStateException("발행 계정이 없습니다");
        }
        return capitals.get(0);
    }
}
