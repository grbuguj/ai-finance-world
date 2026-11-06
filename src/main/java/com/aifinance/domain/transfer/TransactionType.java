package com.aifinance.domain.transfer;

/** 거래 유형. 모든 유형이 동일하게 DEBIT 1건 + CREDIT 1건으로 원장에 기록된다. */
public enum TransactionType {
    OPENING("개설"),        // 발행 계정 → 에이전트 (초기 자본금)
    TRANSFER("송금"),       // 에이전트 → 에이전트
    SPEND("소비"),          // 에이전트 → 가맹점
    REFUND("환불"),         // 가맹점 → 에이전트 (SPEND 역거래)
    INVEST("투자"),         // 에이전트 → 투자시장
    INVEST_RETURN("투자정산"), // 투자시장 → 에이전트 (원금 × (1 + 수익률))
    SALARY("급여");         // 발행 계정 → 에이전트

    private final String label;

    TransactionType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
