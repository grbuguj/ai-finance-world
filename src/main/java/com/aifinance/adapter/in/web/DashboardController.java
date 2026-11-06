package com.aifinance.adapter.in.web;

import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.adapter.in.web.dto.FeedItem;
import com.aifinance.application.port.out.TransferPort;
import com.aifinance.application.service.OutboxRelayService;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.IntegrityUseCase;
import com.aifinance.application.usecase.IntegrityUseCase.IntegrityReport;
import com.aifinance.application.usecase.InvestUseCase;
import com.aifinance.domain.agent.Agent;
import com.aifinance.domain.transfer.TransactionType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 대시보드 화면을 위한 조회 전용 API.
 *  - GET /feed/stream : SSE 실시간 거래 피드
 *  - GET /feed        : 최신 거래 20건
 *  - GET /stats       : 통계 + 복식부기 무결성 요약
 *  - GET /integrity   : 무결성 검증 상세 리포트
 */
@RestController
public class DashboardController {

    private final TransferPort transferPort;
    private final AgentUseCase agentUseCase;
    private final IntegrityUseCase integrityUseCase;
    private final InvestUseCase investUseCase;
    private final OutboxRelayService outboxRelayService;
    private final SimulationEngine simulationEngine;
    private final FeedSseService feedSseService;

    public DashboardController(TransferPort transferPort, AgentUseCase agentUseCase,
                               IntegrityUseCase integrityUseCase, InvestUseCase investUseCase,
                               OutboxRelayService outboxRelayService, SimulationEngine simulationEngine,
                               FeedSseService feedSseService) {
        this.transferPort = transferPort;
        this.agentUseCase = agentUseCase;
        this.integrityUseCase = integrityUseCase;
        this.investUseCase = investUseCase;
        this.outboxRelayService = outboxRelayService;
        this.simulationEngine = simulationEngine;
        this.feedSseService = feedSseService;
    }

    @GetMapping("/feed/stream")
    public SseEmitter stream() {
        return feedSseService.subscribe();
    }

    @GetMapping("/feed")
    public List<FeedItem> feed() {
        return transferPort.findRecent().stream().map(FeedItem::from).toList();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        List<Agent> agents = agentUseCase.findAll();
        BigDecimal totalBalance = agents.stream()
                .map(a -> a.getAccount().getBalance())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        IntegrityReport report = integrityUseCase.check();

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
        stats.put("countByType", byType);
        stats.put("openInvestments", investUseCase.openCount());
        OutboxRelayService.OutboxStats outbox = outboxRelayService.stats();
        stats.put("eventsPublished", outbox.published());
        stats.put("outboxPending", outbox.pending());
        stats.put("outboxDead", outbox.dead());
        stats.put("tick", simulationEngine.getTickCount());
        return stats;
    }

    @GetMapping("/integrity")
    public IntegrityReport integrity() {
        return integrityUseCase.check();
    }
}
