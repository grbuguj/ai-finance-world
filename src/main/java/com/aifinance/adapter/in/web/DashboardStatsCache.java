package com.aifinance.adapter.in.web;

import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.application.port.out.TransferPort;
import com.aifinance.application.service.IntegrityMonitor;
import com.aifinance.application.service.OutboxRelayService;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.IntegrityUseCase.IntegrityReport;
import com.aifinance.application.usecase.InvestUseCase;
import com.aifinance.domain.agent.Agent;
import com.aifinance.domain.transfer.TransactionType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 대시보드 통계 스냅샷 캐시 (TTL + single-flight).
 *
 * /stats 는 거래 전체 COUNT·SUM·GROUP BY 와 무결성 검증을 포함한다. 요청마다 계산하면
 * "열린 탭 수 × 0.6초 폴링" 만큼 원장 전체 집계가 반복된다. 스냅샷은 refresh-ms(기본 1초)에 한 번만 만들고,
 * 그 사이 요청은 같은 스냅샷을 받는다. 무결성 검증은 더 무거워 integrity-max-age(기본 5초) 주기로만 갱신한다.
 * 만료된 스냅샷을 여러 요청이 동시에 보면 한 요청만 계산하고 나머지는 그 결과를 기다려 받는다.
 */
@Component
public class DashboardStatsCache {

    private final TransferPort transferPort;
    private final AgentUseCase agentUseCase;
    private final InvestUseCase investUseCase;
    private final OutboxRelayService outboxRelayService;
    private final SimulationEngine simulationEngine;
    private final IntegrityMonitor integrityMonitor;
    private final long refreshNanos;
    private final Duration integrityMaxAge;

    private volatile Map<String, Object> snapshot;
    private volatile long builtAtNanos;

    public DashboardStatsCache(TransferPort transferPort, AgentUseCase agentUseCase, InvestUseCase investUseCase,
                               OutboxRelayService outboxRelayService, SimulationEngine simulationEngine,
                               IntegrityMonitor integrityMonitor,
                               @Value("${app.dashboard.stats-refresh-ms:1000}") long refreshMs,
                               @Value("${app.dashboard.integrity-max-age-ms:5000}") long integrityMaxAgeMs) {
        this.transferPort = transferPort;
        this.agentUseCase = agentUseCase;
        this.investUseCase = investUseCase;
        this.outboxRelayService = outboxRelayService;
        this.simulationEngine = simulationEngine;
        this.integrityMonitor = integrityMonitor;
        this.refreshNanos = Duration.ofMillis(refreshMs).toNanos();
        this.integrityMaxAge = Duration.ofMillis(integrityMaxAgeMs);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> s = snapshot;
        if (s != null && System.nanoTime() - builtAtNanos < refreshNanos) {
            return s;
        }
        synchronized (this) {
            if (snapshot != null && System.nanoTime() - builtAtNanos < refreshNanos) {
                return snapshot;
            }
            snapshot = Collections.unmodifiableMap(build());
            builtAtNanos = System.nanoTime();
            return snapshot;
        }
    }

    /** 초기화 직후처럼 즉시 반영이 필요할 때 (무결성도 다시 검증) */
    public synchronized void invalidate() {
        integrityMonitor.refresh();
        snapshot = null;
    }

    private Map<String, Object> build() {
        List<Agent> agents = agentUseCase.findAll();
        BigDecimal totalBalance = agents.stream()
                .map(a -> a.getAccount().getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        IntegrityMonitor.Snapshot integrity = integrityMonitor.latest(integrityMaxAge);
        IntegrityReport report = integrity.report();

        Map<String, Long> byType = new LinkedHashMap<>();
        transferPort.countByType().forEach((type, count) -> byType.put(type.name(), count));
        byType.remove(TransactionType.OPENING.name());

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalTransfers", transferPort.count());
        stats.put("totalMoved", transferPort.sumAmount());
        stats.put("totalBalance", totalBalance);
        stats.put("agentCount", agents.size());
        stats.put("totalDebit", report.totalDebit());
        stats.put("totalCredit", report.totalCredit());
        stats.put("balanced", report.balanced());
        stats.put("mismatchedAccounts", report.mismatchedAccountCount());
        stats.put("integrityCheckedAt", integrity.checkedAt());
        stats.put("integrityCheckMs", integrity.durationMillis());
        stats.put("countByType", byType);
        stats.put("openInvestments", investUseCase.openCount());
        OutboxRelayService.OutboxStats outbox = outboxRelayService.stats();
        stats.put("eventsPublished", outbox.published());
        stats.put("outboxPending", outbox.pending());
        stats.put("outboxDead", outbox.dead());
        stats.put("tick", simulationEngine.getTickCount());
        return stats;
    }
}
