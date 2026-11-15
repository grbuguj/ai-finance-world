package com.aifinance.external.settlement;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * 정산 시스템이 수신하는 메시지 스키마. 모르는 필드는 무시한다 → 은행이 필드를 추가해도 소비자가 깨지지 않음.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SettlementMessage(
        Long transferId,
        String txType,
        Long senderAccountId,
        Long receiverAccountId,
        BigDecimal amount,
        String occurredAt
) {
}
