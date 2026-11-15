package com.aifinance.adapter.out.messaging;

import com.aifinance.application.port.out.FaultInjectionPort;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 브로커 장애 재현기. 모든 TransactionEventPort 구현체가 발행 전/후에 이 객체를 거친다.
 *  - beforeSend: 브로커 다운이면 전송 자체가 실패
 *  - afterSend : 전송은 됐지만 ACK 가 유실된 것처럼 예외 → 릴레이가 재발행 → 소비자에 중복 도착
 */
@Component
public class BrokerFaultInjector implements FaultInjectionPort {

    private final AtomicBoolean down = new AtomicBoolean(false);
    private final AtomicInteger ackLosses = new AtomicInteger(0);

    @Override
    public void setBrokerDown(boolean down) {
        this.down.set(down);
    }

    @Override
    public boolean isBrokerDown() {
        return down.get();
    }

    @Override
    public void loseNextAcks(int count) {
        ackLosses.set(Math.max(0, count));
    }

    @Override
    public int remainingAckLosses() {
        return ackLosses.get();
    }

    void beforeSend() {
        if (down.get()) {
            throw new BrokerUnavailableException("브로커 연결 실패 (장애 주입)");
        }
    }

    void afterSend() {
        if (ackLosses.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new BrokerUnavailableException("브로커 ACK 타임아웃 (장애 주입: 메시지는 전달됨)");
        }
    }

    public static class BrokerUnavailableException extends RuntimeException {
        public BrokerUnavailableException(String message) {
            super(message);
        }

        public BrokerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
