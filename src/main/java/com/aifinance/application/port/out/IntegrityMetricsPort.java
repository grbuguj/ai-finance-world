package com.aifinance.application.port.out;

/** 원장 무결성 검증 지표 포트 */
public interface IntegrityMetricsPort {
    void recordCheck(long durationMillis, boolean balanced);
}
