package com.aifinance.domain.reconciliation;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 일마감(EOD) 대사 실행 기록 한 건.
 * "은행 원장"과 "외부 정산 장부"를 같은 기간으로 맞춰 보고, 결과와 차이 내역을 남긴다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "reconciliation_runs", indexes = @Index(name = "idx_recon_business_date", columnList = "business_date"))
public class ReconciliationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    private LocalDateTime windowFrom;
    private LocalDateTime windowTo;

    private long bankCount;
    @Column(precision = 19, scale = 2)
    private BigDecimal bankAmount;

    private long settlementCount;
    @Column(precision = 19, scale = 2)
    private BigDecimal settlementAmount;

    private long missingCount;
    private long inFlightCount;
    private long unexpectedCount;
    private long mismatchCount;
    private long duplicateDeliveries;

    /** 같은 시점의 복식부기 무결성 검증 결과 */
    private boolean ledgerBalanced;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private ReconciliationStatus status;

    private LocalDateTime executedAt;
    private long durationMs;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReconciliationBreakItem> breaks = new ArrayList<>();

    public static ReconciliationRun start(LocalDate businessDate, LocalDateTime from, LocalDateTime to) {
        ReconciliationRun r = new ReconciliationRun();
        r.businessDate = businessDate;
        r.windowFrom = from;
        r.windowTo = to;
        r.executedAt = LocalDateTime.now();
        return r;
    }

    public void recordTotals(long bankCount, BigDecimal bankAmount, long settlementCount, BigDecimal settlementAmount) {
        this.bankCount = bankCount;
        this.bankAmount = bankAmount;
        this.settlementCount = settlementCount;
        this.settlementAmount = settlementAmount;
    }

    public void recordBreakCounts(long missing, long inFlight, long unexpected, long mismatch, long duplicates) {
        this.missingCount = missing;
        this.inFlightCount = inFlight;
        this.unexpectedCount = unexpected;
        this.mismatchCount = mismatch;
        this.duplicateDeliveries = duplicates;
    }

    public void addBreak(Long transferId, String kind, String txType, BigDecimal bankAmount, BigDecimal settlementAmount) {
        breaks.add(new ReconciliationBreakItem(this, transferId, kind, txType, bankAmount, settlementAmount));
    }

    /** 판정: 진짜 차이가 하나라도 있거나 원장이 깨졌으면 UNMATCHED, 전달 중인 것만 있으면 PENDING_IN_FLIGHT */
    public void complete(boolean ledgerBalanced, long durationMs) {
        this.ledgerBalanced = ledgerBalanced;
        this.durationMs = durationMs;
        boolean realBreaks = missingCount > 0 || unexpectedCount > 0 || mismatchCount > 0 || !ledgerBalanced;
        if (realBreaks) {
            this.status = ReconciliationStatus.UNMATCHED;
        } else if (inFlightCount > 0) {
            this.status = ReconciliationStatus.PENDING_IN_FLIGHT;
        } else {
            this.status = ReconciliationStatus.MATCHED;
        }
    }
}
