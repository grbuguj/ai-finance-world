package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.IntegrityUseCase;
import com.aifinance.application.usecase.TransferUseCase;
import com.aifinance.domain.account.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 금액 정밀도 — 원화는 소수가 없다. 컬럼은 scale 2 라서 소수 금액이 들어오면
 * 잔액(계산 후 저장 시 반올림)과 분개(금액 자체를 반올림)가 따로 반올림될 수 있다.
 */
@SpringBootTest(classes = AiFinanceWorldApplication.class)
class AmountPrecisionTest {

    @Autowired SimulationEngine engine;
    @Autowired AgentUseCase agentUseCase;
    @Autowired TransferUseCase transferUseCase;
    @Autowired IntegrityUseCase integrityUseCase;

    Account a;
    Account b;

    @BeforeEach
    void setUp() {
        engine.reset();
        List<Account> accounts = agentUseCase.findAll().stream().map(ag -> ag.getAccount()).toList();
        a = accounts.get(0);
        b = accounts.get(1);
    }

    @Test
    void 소수_금액_송금_후에도_원장이_맞는가() {
        try {
            for (int i = 0; i < 3; i++) {
                transferUseCase.transfer(a.getId(), b.getId(), new BigDecimal("0.005"), "소수 금액 " + i);
            }
        } catch (IllegalArgumentException rejected) {
            return; // 거절했다면 안전
        }
        assertTrue(integrityUseCase.check().balanced(), "소수 금액이 통과됐다면 원장은 맞아야 한다: " + integrityUseCase.check());
    }
}
