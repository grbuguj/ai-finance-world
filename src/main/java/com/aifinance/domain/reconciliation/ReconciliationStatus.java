package com.aifinance.domain.reconciliation;

public enum ReconciliationStatus {
    /** 두 장부가 완전히 일치 */
    MATCHED,
    /** 차이는 전달 중(IN_FLIGHT)인 건뿐 — 잠시 후 재대사하면 일치해야 한다 */
    PENDING_IN_FLIGHT,
    /** 유실·불명·금액 불일치 또는 원장 무결성 위반 발견 — 조사 필요 */
    UNMATCHED
}
