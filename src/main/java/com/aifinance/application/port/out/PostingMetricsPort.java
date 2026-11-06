package com.aifinance.application.port.out;

import com.aifinance.domain.transfer.TransactionType;

/**
 * 거래 기장 지표 포트. 애플리케이션 계층은 Micrometer 를 모르고 이 포트만 안다.
 */
public interface PostingMetricsPort {
    /** 기장 성공: 전체 소요시간, 그 중 DB 행 락(SELECT ... FOR UPDATE) 대기시간 */
    void recordPosted(TransactionType type, long totalNanos, long lockWaitNanos);

    /** 기장 실패: 실패 사유(예외 클래스명) */
    void recordFailed(TransactionType type, String reason);
}
