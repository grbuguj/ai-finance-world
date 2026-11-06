package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.adapter.in.web.DashboardStatsCache;
import com.aifinance.application.port.out.TransferPort;
import com.aifinance.application.service.OutboxRelayService;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.IntegrityUseCase;
import com.aifinance.application.usecase.InvestUseCase;
import com.aifinance.application.usecase.SimulationSetupUseCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 대시보드 /stats 비용이 원장 크기에 따라 어떻게 변하는지 측정한다 (수정 전후 비교).
 *
 *  - 수정 전 경로: 요청마다 무결성 검증 + 거래 전체 COUNT/SUM/GROUP BY (기존 DashboardController 로직 그대로)
 *  - 수정 후 경로: DashboardStatsCache 1초 스냅샷 (요청은 메모리 읽기, 집계는 1초에 한 번)
 *
 * 거래는 두 계좌가 같은 금액을 주고받는 쌍으로 만들어 잔액이 변하지 않게 하고(무결성 유지),
 * INSERT ... SELECT 로 행 수를 두 배씩 늘려 빠르게 대량 데이터를 만든다.
 */
@Tag("benchmark")
@SpringBootTest(classes = AiFinanceWorldApplication.class, properties = "app.outbox.relay.auto=false")
class StatsScaleBenchmark {

    @Autowired SimulationEngine engine;
    @Autowired SimulationSetupUseCase setup;
    @Autowired IntegrityUseCase integrityUseCase;
    @Autowired TransferPort transferPort;
    @Autowired AgentUseCase agentUseCase;
    @Autowired InvestUseCase investUseCase;
    @Autowired OutboxRelayService outboxRelayService;
    @Autowired DashboardStatsCache statsCache;
    @Autowired JdbcTemplate jdbc;

    String dbLabel() {
        return "h2";
    }

    /** H2 는 DB 전체가 JVM 힙에 올라가므로 10만 건까지만 (큰 규모는 MySQL 변형에서) */
    long[] sizes() {
        return new long[]{10_000, 100_000};
    }

    /** 수정 전 /stats 가 요청마다 하던 일 */
    Object oldStats() {
        agentUseCase.findAll();
        integrityUseCase.check();
        transferPort.countByType();
        transferPort.count();
        transferPort.sumAmount();
        investUseCase.openCount();
        return outboxRelayService.stats();
    }

    @Test
    void 원장_크기별_stats_비용() {
        engine.reset();
        List<Long> ids = setup.createFundedAccounts("scale", 2, BigDecimal.valueOf(1_000_000));
        long a = ids.get(0), b = ids.get(1);
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        List<Object[]> seed = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            seed.add(new Object[]{a, b, now});
            seed.add(new Object[]{b, a, now});
        }
        jdbc.batchUpdate("insert into transfers (sender_id, receiver_id, amount, type, tx_type, description, refunded, created_at)"
                + " values (?, ?, 100, 'SUCCESS', 'TRANSFER', 'bulk', false, ?)", seed);

        List<String> rows = new ArrayList<>();
        long ledgeredUpTo = 0;
        for (long target : sizes()) {
            while (bulkCount() < target) {
                jdbc.update("insert into transfers (sender_id, receiver_id, amount, type, tx_type, description, refunded, created_at)"
                        + " select sender_id, receiver_id, amount, type, tx_type, description, refunded, created_at"
                        + " from transfers where description = 'bulk'");
            }
            // 새로 만든 거래의 분개 (거래마다 DEBIT 1건 + CREDIT 1건)
            jdbc.update("insert into ledger_entries (account_id, transfer_id, type, amount, created_at)"
                    + " select sender_id, id, 'DEBIT', amount, created_at from transfers where description = 'bulk' and id > ?", ledgeredUpTo);
            jdbc.update("insert into ledger_entries (account_id, transfer_id, type, amount, created_at)"
                    + " select receiver_id, id, 'CREDIT', amount, created_at from transfers where description = 'bulk' and id > ?", ledgeredUpTo);
            ledgeredUpTo = jdbc.queryForObject("select max(id) from transfers", Long.class);
            long transfers = jdbc.queryForObject("select count(*) from transfers", Long.class);
            long entries = jdbc.queryForObject("select count(*) from ledger_entries", Long.class);

            assertTrue(integrityUseCase.check().balanced(), "대량 데이터도 무결성이 유지돼야 측정이 의미 있다");

            oldStats(); // 예열
            double oldMs = avgMillis(5, () -> oldStats());
            statsCache.invalidate();
            long t0 = System.nanoTime();
            statsCache.snapshot();
            double coldMs = (System.nanoTime() - t0) / 1e6;
            double cachedMs = avgMillis(2_000, () -> statsCache.snapshot());
            // 0.6초 폴링 × 탭 5개 = 초당 8.3회 요청일 때 DB 가 /stats 에 쓰는 시간 비율
            double oldLoad = Math.min(100, oldMs * (5 / 0.6) / 10);
            double newLoad = Math.min(100, coldMs * 1 / 10);   // 1초에 한 번만 집계

            String row = String.format("| %,d | %,d | %.1f | %.1f | %.4f | %.0f%% | %.0f%% |",
                    transfers, entries, oldMs, coldMs, cachedMs, oldLoad, newLoad);
            rows.add(row);
            System.out.println("STATS-SCALE|" + dbLabel() + row);
        }
        System.out.println("STATS-SCALE-REPORT-BEGIN\n| 거래 | 분개 | 수정 전 /stats (ms) | 스냅샷 생성 (ms) | 수정 후 /stats (ms) | DB 점유율 전 (탭 5개) | DB 점유율 후 |\n"
                + "|---:|---:|---:|---:|---:|---:|---:|\n" + String.join("\n", rows) + "\nSTATS-SCALE-REPORT-END");
    }

    private long bulkCount() {
        return jdbc.queryForObject("select count(*) from transfers where description = 'bulk'", Long.class);
    }

    private static double avgMillis(int n, Runnable r) {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) r.run();
        return (System.nanoTime() - t0) / 1e6 / n;
    }
}
