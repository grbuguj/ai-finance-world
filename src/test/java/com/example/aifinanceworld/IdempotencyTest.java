package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.service.IdempotentExecutor;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.IntegrityUseCase;
import com.aifinance.application.usecase.SpendUseCase;
import com.aifinance.application.usecase.TransferUseCase;
import com.aifinance.domain.account.Account;
import com.aifinance.domain.account.AccountType;
import com.aifinance.domain.account.InsufficientBalanceException;
import com.aifinance.domain.idempotency.IdempotencyKeyMismatchException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 멱등성 키 검증 — "네트워크 타임아웃 후 클라이언트가 같은 요청을 재전송" 상황.
 */
@SpringBootTest(classes = AiFinanceWorldApplication.class)
class IdempotencyTest {

    private static final int THREADS = 50;

    @Autowired SimulationEngine engine;
    @Autowired AgentUseCase agentUseCase;
    @Autowired AccountPort accountPort;
    @Autowired TransferUseCase transferUseCase;
    @Autowired SpendUseCase spendUseCase;
    @Autowired IntegrityUseCase integrityUseCase;
    @Autowired JdbcTemplate jdbc;

    Account a;
    Account b;

    @BeforeEach
    void setUp() {
        engine.reset();
        a = agentUseCase.findAll().get(0).getAccount();
        b = agentUseCase.findAll().get(1).getAccount();
    }

    private BigDecimal balanceOf(Account acc) {
        return accountPort.findById(acc.getId()).orElseThrow().getBalance();
    }

    private long transferCountFrom(Account acc) {
        return jdbc.queryForObject("select count(*) from transfers where sender_id = ? and tx_type = 'TRANSFER'",
                Long.class, acc.getId());
    }

    @Test
    void 같은_키_50건_동시_재전송해도_송금은_1건() throws Exception {
        String key = UUID.randomUUID().toString();
        BigDecimal before = balanceOf(a);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IdempotentExecutor.Result>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return transferUseCase.transfer(key, a.getId(), b.getId(), new BigDecimal("10000"), "재전송 테스트");
            }));
        }
        start.countDown();

        Set<Long> transferIds = new HashSet<>();
        int replayed = 0;
        for (Future<IdempotentExecutor.Result> f : futures) {
            IdempotentExecutor.Result r = f.get(30, TimeUnit.SECONDS); // 50건 모두 성공 응답이어야 함
            transferIds.add(r.transfer().getId());
            if (r.replayed()) replayed++;
        }
        pool.shutdownNow();

        assertEquals(1, transferIds.size(), "50건 모두 같은 거래 id 를 돌려받아야 함");
        assertEquals(THREADS - 1, replayed, "첫 1건만 실제 처리, 나머지 49건은 재생(replay)");
        assertEquals(1, transferCountFrom(a), "실제 송금 거래는 1건");
        assertEquals(0, before.subtract(new BigDecimal("10000")).compareTo(balanceOf(a)), "출금은 1번만");
        assertTrue(integrityUseCase.check().balanced());
    }

    @Test
    void 순차_재시도는_첫_결과를_그대로_돌려준다() {
        String key = UUID.randomUUID().toString();
        var first = transferUseCase.transfer(key, a.getId(), b.getId(), new BigDecimal("5000"), "첫 요청");
        var retry = transferUseCase.transfer(key, a.getId(), b.getId(), new BigDecimal("5000.00"), "재시도");

        assertFalse(first.replayed());
        assertTrue(retry.replayed());
        assertEquals(first.transfer().getId(), retry.transfer().getId());
        assertEquals(1, transferCountFrom(a));
    }

    @Test
    void 같은_키에_다른_금액이면_거절() {
        String key = UUID.randomUUID().toString();
        transferUseCase.transfer(key, a.getId(), b.getId(), new BigDecimal("5000"), "원래 요청");

        assertThrows(IdempotencyKeyMismatchException.class,
                () -> transferUseCase.transfer(key, a.getId(), b.getId(), new BigDecimal("9000"), "변조된 요청"));
        assertEquals(1, transferCountFrom(a));
    }

    @Test
    void 같은_키라도_송금과_결제는_서로_다른_요청() {
        String key = UUID.randomUUID().toString();
        Account merchant = accountPort.findByType(AccountType.MERCHANT).get(0);
        transferUseCase.transfer(key, a.getId(), merchant.getId(), new BigDecimal("5000"), "송금");

        assertThrows(IdempotencyKeyMismatchException.class,
                () -> spendUseCase.spend(key, a.getId(), merchant.getId(), new BigDecimal("5000"), "결제"));
    }

    @Test
    void 실패한_요청은_키가_남지_않아_같은_키로_재시도_가능() {
        String key = UUID.randomUUID().toString();
        BigDecimal tooMuch = balanceOf(a).add(BigDecimal.ONE);

        assertThrows(InsufficientBalanceException.class,
                () -> transferUseCase.transfer(key, a.getId(), b.getId(), tooMuch, "잔액 초과"));
        assertEquals(0, jdbc.queryForObject("select count(*) from idempotency_keys where idempotency_key = ?", Long.class, key),
                "실패 시 키도 롤백");

        var ok = transferUseCase.transfer(key, a.getId(), b.getId(), new BigDecimal("1000"), "재시도");
        assertFalse(ok.replayed());
        assertTrue(integrityUseCase.check().balanced());
    }

    @Test
    void 키가_없으면_매번_새_송금() {
        transferUseCase.transfer(null, a.getId(), b.getId(), new BigDecimal("1000"), "1");
        transferUseCase.transfer(null, a.getId(), b.getId(), new BigDecimal("1000"), "2");
        assertEquals(2, transferCountFrom(a));
    }
}
