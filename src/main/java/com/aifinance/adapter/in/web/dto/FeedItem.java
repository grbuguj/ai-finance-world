package com.aifinance.adapter.in.web.dto;

import com.aifinance.domain.transfer.TransactionType;
import com.aifinance.domain.transfer.Transfer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 대시보드 실시간 피드 한 줄. 계좌 id 대신 계좌 소유자 이름(에이전트/가맹점)으로 변환해 전달한다.
 */
public record FeedItem(
        Long id,
        String senderName,
        String receiverName,
        BigDecimal amount,
        String type,
        String txType,
        String txLabel,
        String description,
        LocalDateTime createdAt
) {
    public static FeedItem from(Transfer t) {
        TransactionType txType = t.getTxType() == null ? TransactionType.TRANSFER : t.getTxType();
        return new FeedItem(
                t.getId(),
                nameOf(t.getSender() == null ? null : t.getSender().getName()),
                nameOf(t.getReceiver() == null ? null : t.getReceiver().getName()),
                t.getAmount(),
                t.getType() == null ? null : t.getType().name(),
                txType.name(),
                txType.getLabel(),
                t.getDescription(),
                t.getCreatedAt()
        );
    }

    private static String nameOf(String name) {
        return name == null ? "익명" : name;
    }

    /** SSE 전송용 JSON */
    public String toJson() {
        return "{" +
                "\"id\":" + id + "," +
                "\"senderName\":\"" + esc(senderName) + "\"," +
                "\"receiverName\":\"" + esc(receiverName) + "\"," +
                "\"amount\":" + (amount == null ? "0" : amount.toPlainString()) + "," +
                "\"type\":\"" + esc(type) + "\"," +
                "\"txType\":\"" + esc(txType) + "\"," +
                "\"txLabel\":\"" + esc(txLabel) + "\"," +
                "\"description\":\"" + esc(description) + "\"," +
                "\"createdAt\":\"" + (createdAt == null ? "" : createdAt.toString()) + "\"" +
                "}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
