package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.adapter.in.web.DashboardStatsCache;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.TransferUseCase;
import com.aifinance.domain.account.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** /stats 스냅샷: TTL 안에서는 다시 집계하지 않고, 동시 요청도 한 번만 집계하며, 만료·무효화 후에는 최신 값을 반영한다 */
@SpringBootTest(classes = AiFinanceWorldApplication.class, properties = "app.dashboard.stats-refresh-ms=300")
class DashboardStatsCacheTest {

    @Autowired DashboardStatsCache cache;
    @Autowired SimulationEngine engine;
    @Autowired AgentUseCase agentUseCase;
    @Autowired TransferUseCase transferUseCase;

    @BeforeEach
    void setUp() {
        engine.reset();
        cache.invalidate();
    }

    @Test
    void TTL_안에서는_같은_스냅샷을_돌려준다() {
        Map<String, Object> first = cache.snapshot();
        assertSame(first, cache.snapshot(), "TTL 안에서 다시 집계하면 안 된다");
    }

    @Test
    void 동시_요청_50건도_스냅샷은_하나만_만든다() throws Exception {
        Map<Object, Boolean> distinct = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        for (int i = 0; i < 50; i++) {
            pool.submit(() -> distinct.put(System.identityHashCode(cache.snapshot()), true));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(distinct.size() <= 2, "동시 요청이 각자 집계하면 안 된다 (TTL 경계에서 최대 2개): " + distinct.size());
    }

    @Test
    void 만료되면_새_거래가_반영된다() throws Exception {
        long before = ((Number) cache.snapshot().get("totalTransfers")).longValue();
        List<Account> accounts = agentUseCase.findAll().stream().map(a -> a.getAccount()).toList();
        transferUseCase.transfer(accounts.get(0).getId(), accounts.get(1).getId(), BigDecimal.valueOf(100), "캐시 갱신 확인");

        Thread.sleep(350);
        long after = ((Number) cache.snapshot().get("totalTransfers")).longValue();
        assertEquals(before + 1, after);
        assertNotNull(cache.snapshot().get("integrityCheckedAt"), "무결성 검증 시각이 함께 보여야 한다");
    }
}
