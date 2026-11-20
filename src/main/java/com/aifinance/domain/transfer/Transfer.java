package com.aifinance.domain.transfer;

import com.aifinance.domain.account.Account;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
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

@Immutable // 원장 불변성: 한 번 기록된 거래 행은 UPDATE 되지 않는다. 정정은 반대 거래(REFUND 등)를 새로 추가한다 (ADR/실험: ledger-immutability)
@Entity
@Table(name = "transfers",
        // 한 거래는 정정(환불·투자정산)을 최대 한 번만 가질 수 있다 — 이중 환불을 DB 가 막는 마지막 방어선. (NULL 은 여러 건 허용)
        uniqueConstraints = @UniqueConstraint(name = "uk_transfers_original", columnNames = "original_transfer_id"),
        indexes = {
        // 통계·감사용 실제 생성 시각 조회
        @Index(name = "idx_transfers_created_at", columnList = "created_at"),
        // 대사(영업일 기간 조회)용 가상 발생 시각. 없으면 일마감 대사가 transfers 전체를 스캔한다
        @Index(name = "idx_transfers_occurred_at", columnList = "occurred_at"),
        // 환불 후보 조회: sender + 유형 (환불 여부는 uk_transfers_original 로 NOT EXISTS 판정)
        @Index(name = "idx_transfers_sender_type", columnList = "sender_id, tx_type")
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

    /** 실제(wall-clock) 생성 시각 — 감사·운영용 */
    @CreatedDate
    private LocalDateTime createdAt;

    /** 가상 시각(VirtualClock) — 대사의 "그날 거래" 판별 기준. LedgerPostingService 가 기록 시점에 직접 채운다 */
    private LocalDateTime occurredAt;

}
