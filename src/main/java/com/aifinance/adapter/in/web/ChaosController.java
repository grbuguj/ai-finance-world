package com.aifinance.adapter.in.web;

import com.aifinance.application.port.out.FaultInjectionPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 장애 주입 API (시연·검증용, app.chaos.enabled=true 일 때만 노출).
 *  - POST /chaos/broker/down          : 브로커 장애 시작 → 거래는 계속 성공, Outbox 에 쌓임
 *  - POST /chaos/broker/up            : 복구 → 릴레이가 쌓인 이벤트를 순서대로 발행
 *  - POST /chaos/broker/ack-loss?n=5  : 다음 n건 ACK 유실 → 재발행 → 정산 시스템이 중복 제거
 *  - GET  /chaos                      : 현재 상태
 */
@RestController
@RequestMapping("/chaos")
@ConditionalOnProperty(name = "app.chaos.enabled", havingValue = "true")
public class ChaosController {

    private final FaultInjectionPort faults;

    public ChaosController(FaultInjectionPort faults) {
        this.faults = faults;
    }

    @GetMapping
    public Map<String, Object> status() {
        return Map.of("brokerDown", faults.isBrokerDown(), "remainingAckLosses", faults.remainingAckLosses());
    }

    @PostMapping("/broker/down")
    public Map<String, Object> down() {
        faults.setBrokerDown(true);
        return status();
    }

    @PostMapping("/broker/up")
    public Map<String, Object> up() {
        faults.setBrokerDown(false);
        return status();
    }

    @PostMapping("/broker/ack-loss")
    public Map<String, Object> ackLoss(@RequestParam(defaultValue = "5") int n) {
        faults.loseNextAcks(n);
        return status();
    }
}
