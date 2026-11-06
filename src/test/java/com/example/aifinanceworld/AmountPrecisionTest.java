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
 * 잔액(계산 후 저장 시 반올림)과 분개(금액 자체를 반올림)가 따로 반올림된다.
 * 수정 전 재현: 0.005원 × 3회 → 보낸 계좌 잔액 2,900,000.00 그대로, 원장 기준 2,899,999.97, 시스템 총액 +0.03원.
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
    void 원_단위가_아닌_금액은_거절하고_잔액과_원장은_그대로다() {
        BigDecimal before = integrityUseCase.check().sumOfAllBalances();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> transferUseCase.transfer(a.getId(), b.getId(), new BigDecimal("0.005"), "소수 금액"));
        assertTrue(e.getMessage().contains("원 단위"));
        assertThrows(IllegalArgumentException.class,
                () -> transferUseCase.transfer(a.getId(), b.getId(), new BigDecimal("1000.5"), "소수 금액"));

        assertTrue(integrityUseCase.check().balanced());
        assertEquals(0, before.compareTo(integrityUseCase.check().sumOfAllBalances()));
    }

    @Test
    void 소수점_아래가_0_인_금액은_정수로_취급해_허용한다() {
        transferUseCase.transfer(a.getId(), b.getId(), new BigDecimal("1000.00"), "1000.00 원");
        assertTrue(integrityUseCase.check().balanced());
    }
}
