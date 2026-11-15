package com.aifinance.application.port.out;

/** Outbox 릴레이 지표 포트. */
public interface RelayMetricsPort {
    /** 발행 성공: 이벤트 생성부터 브로커 ack 까지 걸린 시간(발행 지연) */
    void recordPublished(long lagMillis);

    /** 발행 실패 (재시도 예정) */
    void recordPublishFailed(String reason);

    /** 최대 재시도 초과로 DEAD 격리 */
    void recordDead();
}
