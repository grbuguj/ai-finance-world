package com.aifinance.adapter.in.web;

import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.application.port.out.TransferPort;
import com.aifinance.application.usecase.IntegrityUseCase;
import com.aifinance.application.usecase.IntegrityUseCase.IntegrityReport;
import com.aifinance.application.usecase.ReconciliationUseCase;
import com.aifinance.application.usecase.ReconciliationUseCase.RunView;
import com.aifinance.domain.transfer.TransactionType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/simulation")
public class SimulationController {

    private final SimulationEngine simulationEngine;
    private final DashboardStatsCache statsCache;
    private final TransferPort transferPort;
    private final IntegrityUseCase integrityUseCase;
    private final ReconciliationUseCase reconciliationUseCase;

    public SimulationController(SimulationEngine simulationEngine, DashboardStatsCache statsCache,
                               TransferPort transferPort, IntegrityUseCase integrityUseCase,
                               ReconciliationUseCase reconciliationUseCase) {
        this.simulationEngine = simulationEngine;
        this.statsCache = statsCache;
        this.transferPort = transferPort;
        this.integrityUseCase = integrityUseCase;
        this.reconciliationUseCase = reconciliationUseCase;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "state", simulationEngine.getState().name(),
                "tickIntervalMs", simulationEngine.getTickIntervalMs(),
                "tick", simulationEngine.getTickCount(),
                "virtualNow", simulationEngine.getVirtualNow().toString(),
                "virtualDay", simulationEngine.getVirtualDay(),
                "virtualDaysElapsed", simulationEngine.getVirtualDaysElapsed(),
                "minutesPerTick", simulationEngine.getMinutesPerTick(),
                "virtualStart", simulationEngine.getVirtualStart().toString()
        );
    }

    /** 설정 화면 → "시작" — 에이전트 수를 골라 월드를 만들고 RUNNING 으로 전환 (SETUP 상태에서만 가능) */
    @PostMapping("/setup")
    public ResponseEntity<Map<String, Object>> setup(@RequestParam(defaultValue = "10") int agentCount) {
        return guarded(() -> {
            simulationEngine.setup(agentCount);
            return Map.of("state", simulationEngine.getState().name(), "agentCount", agentCount);
        });
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start() {
        return guarded(() -> {
            simulationEngine.start();
            return Map.of("state", simulationEngine.getState().name());
        });
    }

    @PostMapping("/pause")
    public ResponseEntity<Map<String, Object>> pause() {
        return guarded(() -> {
            simulationEngine.pause();
            return Map.of("state", simulationEngine.getState().name());
        });
    }

    /** 정지 = 종료 (ENDED). 더 이상 틱이 돌지 않고, 이후 /simulation/summary 로 종료 요약을 조회한다 */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        return guarded(() -> {
            simulationEngine.stop();
            return Map.of("state", simulationEngine.getState().name());
        });
    }

    /** "새 시뮬레이션" — ENDED 에서만 호출 가능. 데이터를 초기화하고 SETUP 으로 돌아간다 */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        return guarded(() -> {
            simulationEngine.newSimulation();
            statsCache.invalidate(); // 초기화 결과는 1초 스냅샷을 기다리지 않고 바로 보이게
            return Map.of("state", simulationEngine.getState().name());
        });
    }

    @GetMapping("/speed")
    public Map<String, Object> getSpeed() {
        return Map.of("tickIntervalMs", simulationEngine.getTickIntervalMs());
    }

    @PostMapping("/speed")
    public Map<String, Object> setSpeed(@RequestParam long ms) {
        simulationEngine.setTickIntervalMs(ms);
        return Map.of("tickIntervalMs", simulationEngine.getTickIntervalMs());
    }

    /** 종료 요약 — 총 거래 수, 유형별 건수, 원장 무결성 4조건, 마지막 대사 결과, 가상 경과 일수 */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        IntegrityReport integrity = integrityUseCase.check();
        RunView lastReconciliation = reconciliationUseCase.latest();

        Map<String, Long> byType = new LinkedHashMap<>();
        transferPort.countByType().forEach((type, count) -> byType.put(type.name(), count));
        byType.remove(TransactionType.OPENING.name());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", simulationEngine.getState().name());
        result.put("totalTransfers", transferPort.count());
        result.put("countByType", byType);
        result.put("integrityBalanced", integrity.balanced());
        result.put("integrityDebitEqualsCredit", integrity.debitEqualsCredit());
        result.put("transfersWithoutTwoEntries", integrity.transfersWithoutTwoEntries());
        result.put("mismatchedAccountCount", integrity.mismatchedAccountCount());
        result.put("sumOfAllBalances", integrity.sumOfAllBalances());
        result.put("lastReconciliation", lastReconciliation);
        result.put("virtualDaysElapsed", simulationEngine.getVirtualDaysElapsed());
        return result;
    }

    private ResponseEntity<Map<String, Object>> guarded(java.util.function.Supplier<Map<String, Object>> action) {
        try {
            return ResponseEntity.ok(action.get());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("state", simulationEngine.getState().name(), "error", e.getMessage()));
        }
    }
}
