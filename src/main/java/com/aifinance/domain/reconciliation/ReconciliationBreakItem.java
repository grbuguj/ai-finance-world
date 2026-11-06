package com.aifinance.domain.reconciliation;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** 대사에서 발견된 차이 한 건 (조사 대상) */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "reconciliation_breaks")
public class ReconciliationBreakItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id")
    private ReconciliationRun run;

    private Long transferId;

    @Column(length = 30)
    private String kind;

    @Column(length = 20)
    private String txType;

    @Column(precision = 19, scale = 2)
    private BigDecimal bankAmount;

    @Column(precision = 19, scale = 2)
    private BigDecimal settlementAmount;

    ReconciliationBreakItem(ReconciliationRun run, Long transferId, String kind, String txType,
                            BigDecimal bankAmount, BigDecimal settlementAmount) {
        this.run = run;
        this.transferId = transferId;
        this.kind = kind;
        this.txType = txType;
        this.bankAmount = bankAmount;
        this.settlementAmount = settlementAmount;
    }
}
