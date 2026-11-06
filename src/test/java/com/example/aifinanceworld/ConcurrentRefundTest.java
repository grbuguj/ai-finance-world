package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.IntegrityUseCase;
import com.aifinance.application.usecase.RefundUseCase;
import com.aifinance.application.usecase.SpendUseCase;
import com.aifinance.domain.account.Account;
import com.aifinance.domain.account.AccountType;
import com.aifinance.domain.transfer.Transfer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 같은 소비 건에 환불 요청 20건이 동시에 들어와도 정확히 1건만 성공해야 한다 (이중 환불 방지).
 */
@SpringBootTest(classes = AiFinanceWorldApplication.class)
class ConcurrentRefundTest {

    private static final int THREADS = 20;

    @Autowired SimulationEngine engine;
    @Autowired AgentUseCase agentUseCase;
    @Autowired AccountPort accountPort;
    @Autowired SpendUseCase spendUseCase;
    @Autowired RefundUseCase refundUseCase;
    @Autowired IntegrityUseCase integrityUseCase;

    @Test
    void 동시_환불_20건_중_1건만_성공() throws Exception {
        engine.reset();
        Account agent = agentUseCase.findAll().get(0).getAccount();
        Account merchant = accountPort.findByType(AccountType.MERCHANT).get(0);

        BigDecimal before = accountPort.findById(agent.getId()).orElseThrow().getBalance();
        Transfer spend = spendUseCase.spend(agent.getId(), merchant.getId(), new BigDecimal("70000"), "동시 환불 테스트");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    refundUseCase.refund(spend.getId());
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            }));
        }
        start.countDown();

        int success = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(30, TimeUnit.SECONDS)) success++;
        }
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, success, "환불은 정확히 1건만 성공");
        BigDecimal after = accountPort.findById(agent.getId()).orElseThrow().getBalance();
        assertEquals(0, before.compareTo(after), "잔액은 소비 전과 같아야 함 (이중 환불 없음)");
        assertTrue(integrityUseCase.check().balanced());
    }
}
