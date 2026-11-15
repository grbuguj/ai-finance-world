package com.aifinance.application.port.out;

/**
 * 장애 주입(Chaos) 포트 — 메시지 브로커 장애를 재현해 Outbox 의 유실 방지·중복 제거를 검증한다.
 * 운영 환경에서는 app.chaos.enabled=false 로 엔드포인트를 노출하지 않는다.
 */
public interface FaultInjectionPort {

    /** 브로커 전체 장애 (모든 발행이 실패) */
    void setBrokerDown(boolean down);

    boolean isBrokerDown();

    /**
     * ACK 유실: 브로커는 메시지를 받았지만 응답이 오지 않은 상황을 count 번 재현한다.
     * 릴레이는 실패로 보고 재발행하므로 소비자 쪽에 중복이 도착한다.
     */
    void loseNextAcks(int count);

    int remainingAckLosses();
}
