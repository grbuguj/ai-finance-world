package com.aifinance.domain.outbox;

public enum OutboxStatus {
    /** 아직 브로커에 전달되지 않음 (재시도 대기 포함) */
    PENDING,
    /** 브로커가 수신을 확인(ack)함 */
    PUBLISHED,
    /** 최대 재시도 횟수를 넘김 — 운영자 확인이 필요 (알람 대상) */
    DEAD
}
