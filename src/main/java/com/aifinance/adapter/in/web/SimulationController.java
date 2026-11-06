package com.aifinance.adapter.in.web;

import com.aifinance.adapter.in.scheduler.SimulationEngine;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/simulation")
public class SimulationController {

    private final SimulationEngine simulationEngine;
    private final DashboardStatsCache statsCache;

    public SimulationController(SimulationEngine simulationEngine, DashboardStatsCache statsCache) {
        this.simulationEngine = simulationEngine;
        this.statsCache = statsCache;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "state", simulationEngine.getState().name(),
                "tickIntervalMs", simulationEngine.getTickIntervalMs()
        );
    }

    @PostMapping("/start")
    public Map<String, Object> start() {
        simulationEngine.start();
        return Map.of("state", simulationEngine.getState().name());
    }

    @PostMapping("/pause")
    public Map<String, Object> pause() {
        simulationEngine.pause();
        return Map.of("state", simulationEngine.getState().name());
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        simulationEngine.stop();
        return Map.of("state", simulationEngine.getState().name());
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        simulationEngine.reset();
        statsCache.invalidate(); // 초기화 결과는 1초 스냅샷을 기다리지 않고 바로 보이게
        return Map.of("state", simulationEngine.getState().name());
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
}
