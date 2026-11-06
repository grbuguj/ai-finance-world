package com.aifinance.application.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 거래가 원장에 기록되었다는 이벤트. 커밋 이후(AFTER_COMMIT)에만 외부로 전달된다.
 */
public record TransactionPostedEvent(
        Long transferId,
        String txType,
        Long senderAccountId,
        String senderName,
        Long receiverAccountId,
        String receiverName,
        BigDecimal amount,
        BigDecimal senderBalanceAfter,
        BigDecimal receiverBalanceAfter,
        String description,
        LocalDateTime occurredAt
) {
    public String toJson() {
        return "{" +
                "\"transferId\":" + transferId + "," +
                "\"txType\":\"" + txType + "\"," +
                "\"senderAccountId\":" + senderAccountId + "," +
                "\"senderName\":\"" + esc(senderName) + "\"," +
                "\"receiverAccountId\":" + receiverAccountId + "," +
                "\"receiverName\":\"" + esc(receiverName) + "\"," +
                "\"amount\":" + amount.toPlainString() + "," +
                "\"description\":\"" + esc(description) + "\"," +
                "\"occurredAt\":\"" + occurredAt + "\"" +
                "}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
