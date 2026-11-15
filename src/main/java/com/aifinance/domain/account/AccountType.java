package com.aifinance.domain.account;

/**
 * 계좌 유형.
 * AGENT 를 제외한 나머지는 은행 바깥 세계를 표현하는 "시스템 계좌"로, 잔액이 음수가 될 수 있다.
 * (가맹점이 환불을 내주거나, 투자시장이 수익을 지급하거나, 발행 계정이 급여를 지급하는 경우)
 * 덕분에 외부로 나가는 돈도 반드시 상대 계좌를 가지게 되어 복식부기가 닫힌 시스템이 된다.
 */
public enum AccountType {
    AGENT,      // 에이전트 개인 계좌
    MERCHANT,   // 외부 가맹점 (쿠팡, 무신사 ...)
    MARKET,     // 투자 시장 (한국증권)
    CAPITAL;    // 발행 계정 (초기 자본금, 급여 지급)

    public boolean isSystem() {
        return this != AGENT;
    }
}
