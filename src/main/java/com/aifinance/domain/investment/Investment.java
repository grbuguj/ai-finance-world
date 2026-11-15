package com.aifinance.domain.investment;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 투자 포지션. 납입(INVEST) 시 생성되고, 만기 틱이 지나면 수익률 r ~ N(μ, σ²)로 정산(INVEST_RETURN)된다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)

@Entity
@Table(name = "investments")
public class Investment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long accountId;

    private BigDecimal principal;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private InvestmentStatus status = InvestmentStatus.OPEN;

    private long openedTick;
    private long maturesAtTick;

    private Long investTransferId;
    private Long settleTransferId;

    @Column(precision = 10, scale = 6)
    private BigDecimal returnRate;

    private BigDecimal payout;

    @CreatedDate
    private LocalDateTime createdAt;

    private LocalDateTime settledAt;

    /** 수익률을 적용한 정산 금액. 원금 전액 손실(-100%) 아래로는 내려가지 않는다. */
    public BigDecimal payoutFor(BigDecimal rate) {
        BigDecimal r = rate.max(BigDecimal.ONE.negate());
        return principal.multiply(BigDecimal.ONE.add(r)).setScale(0, RoundingMode.DOWN);
    }

    public void settle(BigDecimal rate, BigDecimal payout, Long settleTransferId) {
        if (status != InvestmentStatus.OPEN) {
            throw new IllegalStateException("이미 정산된 투자입니다 (투자 " + id + ")");
        }
        this.status = InvestmentStatus.SETTLED;
        this.returnRate = rate.max(BigDecimal.ONE.negate());
        this.payout = payout;
        this.settleTransferId = settleTransferId;
        this.settledAt = LocalDateTime.now();
    }
}
