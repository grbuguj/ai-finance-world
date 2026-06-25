package com.aifinance.adapter.in.init;

import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.port.out.AgentPort;
import com.aifinance.domain.account.Account;
import com.aifinance.domain.agent.Agent;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class DataInitializer implements ApplicationRunner {

    private final AgentPort agentPort;
    private final AccountPort accountPort;


    public DataInitializer(AgentPort agentPort, AccountPort accountPort) {
        this.agentPort = agentPort;
        this.accountPort = accountPort;
    }


    @Override
    public void run(ApplicationArguments args) throws Exception {

        Account account1 = accountPort.save(Account.builder().balance(new BigDecimal("2900000")).build());
        agentPort.save(Agent.builder().name("일론머스크").salary(2900000L).account(account1).chanceSpend(0.80).chancePay(0.40).chanceInvest(0.10).chanceRefund(0.20).build());

        Account account2 = accountPort.save(Account.builder().balance(new BigDecimal("1200000")).build());
        agentPort.save(Agent.builder().name("이재용").salary(1200000L).account(account2).chanceSpend(0.30).chancePay(0.20).chanceInvest(0.20).chanceRefund(0.10).build());

        Account account3 = accountPort.save(Account.builder().balance(new BigDecimal("4200000")).build());
        agentPort.save(Agent.builder().name("워런버핏").salary(4200000L).account(account3).chanceSpend(0.40).chancePay(0.20).chanceInvest(0.90).chanceRefund(0.10).build());

        Account account4 = accountPort.save(Account.builder().balance(new BigDecimal("3500000")).build());
        agentPort.save(Agent.builder().name("김범수").salary(3500000L).account(account4).chanceSpend(0.40).chancePay(0.80).chanceInvest(0.10).chanceRefund(0.10).build());

        Account account5 = accountPort.save(Account.builder().balance(new BigDecimal("1600000")).build());
        agentPort.save(Agent.builder().name("손흥민").salary(1600000L).account(account5).chanceSpend(0.75).chancePay(0.30).chanceInvest(0.05).chanceRefund(0.30).build());

        Account account6 = accountPort.save(Account.builder().balance(new BigDecimal("4000000")).build());
        agentPort.save(Agent.builder().name("유재석").salary(4000000L).account(account6).chanceSpend(0.60).chancePay(0.30).chanceInvest(0.20).chanceRefund(0.10).build());

        Account account7 = accountPort.save(Account.builder().balance(new BigDecimal("2700000")).build());
        agentPort.save(Agent.builder().name("아이유").salary(2700000L).account(account7).chanceSpend(0.70).chancePay(0.20).chanceInvest(0.10).chanceRefund(0.20).build());

        Account account8 = accountPort.save(Account.builder().balance(new BigDecimal("3100000")).build());
        agentPort.save(Agent.builder().name("박찬호").salary(3100000L).account(account8).chanceSpend(0.30).chancePay(0.20).chanceInvest(0.40).chanceRefund(0.10).build());

        Account account9 = accountPort.save(Account.builder().balance(new BigDecimal("2600000")).build());
        agentPort.save(Agent.builder().name("김태희").salary(2600000L).account(account9).chanceSpend(0.95).chancePay(0.20).chanceInvest(0.00).chanceRefund(0.50).build());

        Account account10 = accountPort.save(Account.builder().balance(new BigDecimal("3300000")).build());
        agentPort.save(Agent.builder().name("이순신").salary(3300000L).account(account10).chanceSpend(0.40).chancePay(0.30).chanceInvest(0.20).chanceRefund(0.05).build());

    }
}
