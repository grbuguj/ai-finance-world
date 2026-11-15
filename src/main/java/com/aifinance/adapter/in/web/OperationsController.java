package com.aifinance.adapter.in.web;

import com.aifinance.application.service.OutboxRelayService;
import com.aifinance.application.usecase.ReconciliationUseCase;
import com.aifinance.application.usecase.ReconciliationUseCase.RunView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 운영 API — Outbox 상태 확인/조치, 대사 실행·조회.
 *  - GET  /ops/outbox                : PENDING / PUBLISHED / DEAD 건수, 가장 오래된 대기 이벤트 나이
 *  - POST /ops/outbox/relay          : 릴레이 즉시 1회 실행
 *  - POST /ops/outbox/requeue-dead   : DEAD 이벤트 재발행 대기열로
 *  - POST /ops/reconciliation        : 대사 실행 (date 없으면 오늘 0시~지금)
 *  - GET  /ops/reconciliation        : 최근 대사 20건
 *  - GET  /ops/reconciliation/latest : 최근 대사 상세 (차이 내역 포함)
 */
@RestController
@RequestMapping("/ops")
public class OperationsController {

    private final OutboxRelayService relayService;
    private final ReconciliationUseCase reconciliationUseCase;

    public OperationsController(OutboxRelayService relayService, ReconciliationUseCase reconciliationUseCase) {
        this.relayService = relayService;
        this.reconciliationUseCase = reconciliationUseCase;
    }

    @GetMapping("/outbox")
    public OutboxRelayService.OutboxStats outbox() {
        return relayService.stats();
    }

    @PostMapping("/outbox/relay")
    public OutboxRelayService.RelayResult relay() {
        return relayService.relayOnce();
    }

    @PostMapping("/outbox/requeue-dead")
    public Map<String, Integer> requeueDead() {
        return Map.of("requeued", relayService.requeueDead());
    }

    @PostMapping("/reconciliation")
    public RunView reconcile(@RequestParam(required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return date == null ? reconciliationUseCase.reconcileToday() : reconciliationUseCase.reconcileDay(date);
    }

    @GetMapping("/reconciliation")
    public List<RunView> recent() {
        return reconciliationUseCase.recent();
    }

    @GetMapping("/reconciliation/latest")
    public ResponseEntity<RunView> latest() {
        RunView latest = reconciliationUseCase.latest();
        return latest == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(latest);
    }
}
