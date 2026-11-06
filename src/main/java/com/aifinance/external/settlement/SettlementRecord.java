package com.aifinance.external.settlement;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 외부 정산 시스템의 장부 한 줄.
 *
 * 은행 코어와는 별개의 시스템이라는 가정이다. (같은 DB 를 쓰는 건 시뮬레이션 편의일 뿐, 코어는 이 테이블을 직접 모른다)
 * PK 가 은행 거래 id 이므로, 같은 거래 이벤트가 몇 번 도착하든 장부에는 한 줄만 남는다 — 멱등 소비자의 핵심.
 */
@Entity
@Table(name = "settlement_records", indexes = @Index(name = "idx_settlement_occurred_at", columnList = "occurred_at"))
public class SettlementRecord {

    @Id
    private Long transferId;

    @Column(length = 20, nullable = false)
    private String txType;

    private Long senderAccountId;

    private Long receiverAccountId;

    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    /** 은행에서 거래가 발생한 시각 (대사 기준일 판별용) */
    private LocalDateTime occurredAt;

    /** 정산 시스템이 처음 수신한 시각 */
    private LocalDateTime receivedAt;

    /** 같은 이벤트가 도착한 총 횟수 (1 이면 중복 없음) */
    private int deliveryCount;

    protected SettlementRecord() {
    }

    public SettlementRecord(Long transferId, String txType, Long senderAccountId, Long receiverAccountId,
                            BigDecimal amount, LocalDateTime occurredAt, LocalDateTime receivedAt) {
        this.transferId = transferId;
        this.txType = txType;
        this.senderAccountId = senderAccountId;
        this.receiverAccountId = receiverAccountId;
        this.amount = amount;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
        this.deliveryCount = 1;
    }

    public void markRedelivered() {
        this.deliveryCount++;
    }

    public Long getTransferId() { return transferId; }
    public String getTxType() { return txType; }
    public Long getSenderAccountId() { return senderAccountId; }
    public Long getReceiverAccountId() { return receiverAccountId; }
    public BigDecimal getAmount() { return amount; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public int getDeliveryCount() { return deliveryCount; }
}
