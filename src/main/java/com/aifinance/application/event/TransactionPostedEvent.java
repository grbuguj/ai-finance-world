package com.aifinance.application.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 거래가 원장에 기록되었다는 이벤트.
 *  - 외부 전달: toJson() 결과가 Outbox 에 저장되고 릴레이가 브로커로 보낸다
 *  - 내부 전달: 커밋 후(AFTER_COMMIT) 잔액 캐시 갱신
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
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
