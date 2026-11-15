package com.aifinance.domain.account;

import java.math.BigDecimal;

public class InsufficientBalanceException extends RuntimeException {
    public InsufficientBalanceException(Long accountId, BigDecimal balance, BigDecimal amount) {
        super("잔액 부족 (계좌 " + accountId + ", 잔액 " + balance.toPlainString() + ", 요청 " + amount.toPlainString() + ")");
    }
}
