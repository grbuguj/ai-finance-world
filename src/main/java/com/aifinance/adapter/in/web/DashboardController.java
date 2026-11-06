package com.aifinance.adapter.in.web;

import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.adapter.in.web.dto.FeedItem;
import com.aifinance.application.port.out.TransferPort;
import com.aifinance.application.service.OutboxRelayService;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.IntegrityUseCase;
import com.aifinance.application.usecase.IntegrityUseCase.IntegrityReport;
import com.aifinance.application.usecase.InvestUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 대시보드 화면을 위한 조회 전용 API.
 *  - GET /feed/stream : SSE 실시간 거래 피드
 *  - GET /feed        : 최신 거래 20건
 *  - GET /stats       : 통계 + 복식부기 무결성 요약 (1초 스냅샷, 무결성은 최대 5초 전 검증 결과)
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
    private final DashboardStatsCache statsCache;

    public DashboardController(TransferPort transferPort, AgentUseCase agentUseCase,
                               IntegrityUseCase integrityUseCase, InvestUseCase investUseCase,
                               OutboxRelayService outboxRelayService, SimulationEngine simulationEngine,
                               FeedSseService feedSseService, DashboardStatsCache statsCache) {
        this.transferPort = transferPort;
        this.agentUseCase = agentUseCase;
        this.integrityUseCase = integrityUseCase;
        this.investUseCase = investUseCase;
        this.outboxRelayService = outboxRelayService;
        this.simulationEngine = simulationEngine;
        this.feedSseService = feedSseService;
        this.statsCache = statsCache;
    }

    @GetMapping("/feed/stream")
    public SseEmitter stream() {
        return feedSseService.subscribe();
    }

    @GetMapping("/feed")
    public List<FeedItem> feed() {
        return transferPort.findRecent().stream().map(FeedItem::from).toList();
    }

    /** 1초 TTL 스냅샷 — 요청 수와 무관하게 집계는 1초에 한 번 (DashboardStatsCache 참고) */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return statsCache.snapshot();
    }

    @GetMapping("/integrity")
    public IntegrityReport integrity() {
        return integrityUseCase.check();
    }
}
