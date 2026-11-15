package com.aifinance.domain.transfer;

import com.aifinance.domain.account.Account;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 하나의 거래(송금/소비/투자/환불/급여 ...) 이력.
 * 원장(LedgerEntry) 2건이 transfer_id 로 이 거래를 가리킨다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)

@Entity
@Table(name = "transfers", indexes = {
        // 대사(기간 조회)·통계용. 없으면 일마감 대사가 transfers 전체를 스캔한다
        @Index(name = "idx_transfers_created_at", columnList = "created_at"),
        // 환불 대상 조회: sender + 유형 + 환불여부 (findTop5BySenderIdAndTxTypeAndRefundedFalse...)
        @Index(name = "idx_transfers_sender_type_refunded", columnList = "sender_id, tx_type, refunded")
})

public class Transfer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "sender_id")
    private Account sender;

    @ManyToOne
    @JoinColumn(name = "receiver_id")
    private Account receiver;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private TransferStatus type;

    // 거래 유형 (송금/소비/투자/환불 ...)
    @Enumerated(EnumType.STRING)
    @Column(name = "tx_type")
    @Builder.Default
    private TransactionType txType = TransactionType.TRANSFER;

    // 거래 사유 / 결제 내역
    @Column(length = 255)
    private String description;

    // 환불 거래일 때 원거래 id
    private Long originalTransferId;

    // 소비 거래가 환불되었는지 (중복 환불 방지)
    @Builder.Default
    private boolean refunded = false;

    @CreatedDate
    private LocalDateTime createdAt;

    public void describe(String description) {
        this.description = description;
    }

    /** 환불 처리 표시. 소비 거래만, 한 번만 환불할 수 있다. */
    public void markRefunded() {
        if (txType != TransactionType.SPEND) {
            throw new IllegalStateException("소비 거래만 환불할 수 있습니다 (거래 " + id + ", 유형 " + txType + ")");
        }
        if (refunded) {
            throw new IllegalStateException("이미 환불된 거래입니다 (거래 " + id + ")");
        }
        this.refunded = true;
    }
}
