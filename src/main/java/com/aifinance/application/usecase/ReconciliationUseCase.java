package com.aifinance.application.usecase;

import com.aifinance.application.port.out.ReconciliationPort;
import com.aifinance.application.port.out.SettlementLedgerPort;
import com.aifinance.application.port.out.SettlementLedgerPort.BreakKind;
import com.aifinance.application.port.out.SettlementLedgerPort.Totals;
import com.aifinance.domain.reconciliation.ReconciliationBreakItem;
import com.aifinance.domain.reconciliation.ReconciliationRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 대사(Reconciliation) — 은행 원장과 외부 정산 장부가 같은 거래를 같은 금액으로 갖고 있는지 확인한다.
 *
 * 금융권에서 "시스템이 맞게 돌았다"의 최종 증거는 로그가 아니라 대사 결과다.
 * Outbox 로 유실을 막았다면, 그 사실을 매일 대사로 증명한다.
 *
 * 판정 기준
 *  - 건수/금액 합계 비교 (총괄 대사)
 *  - 거래 id 단위 비교 (명세 대사): 누락 / 불명 / 금액 불일치
 *  - Outbox 에 PENDING 으로 남은 건은 "전달 중"으로 분리 → 오탐 방지
 *  - 같은 시점 복식부기 무결성까지 함께 확인
 */
@Service
public class ReconciliationUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationUseCase.class);
    private static final int MAX_BREAK_ITEMS = 100;

    public record BreakView(Long transferId, String kind, String txType, BigDecimal bankAmount, BigDecimal settlementAmount) {}

    public record RunView(Long id, LocalDate businessDate, LocalDateTime windowFrom, LocalDateTime windowTo,
                          String status, long bankCount, BigDecimal bankAmount,
                          long settlementCount, BigDecimal settlementAmount,
                          long missingCount, long inFlightCount, long unexpectedCount, long mismatchCount,
                          long duplicateDeliveries, boolean ledgerBalanced,
                          LocalDateTime executedAt, long durationMs, List<BreakView> breaks) {

        static RunView of(ReconciliationRun r, boolean withBreaks) {
            List<BreakView> items = withBreaks
                    ? r.getBreaks().stream().map(RunView::toView).toList()
                    : List.of();
            return new RunView(r.getId(), r.getBusinessDate(), r.getWindowFrom(), r.getWindowTo(),
                    r.getStatus().name(), r.getBankCount(), r.getBankAmount(),
                    r.getSettlementCount(), r.getSettlementAmount(),
                    r.getMissingCount(), r.getInFlightCount(), r.getUnexpectedCount(), r.getMismatchCount(),
                    r.getDuplicateDeliveries(), r.isLedgerBalanced(), r.getExecutedAt(), r.getDurationMs(), items);
        }

        private static BreakView toView(ReconciliationBreakItem b) {
            return new BreakView(b.getTransferId(), b.getKind(), b.getTxType(), b.getBankAmount(), b.getSettlementAmount());
        }
    }

    private final SettlementLedgerPort settlementLedger;
    private final ReconciliationPort reconciliationPort;
    private final IntegrityUseCase integrityUseCase;

    public ReconciliationUseCase(SettlementLedgerPort settlementLedger, ReconciliationPort reconciliationPort,
                                 IntegrityUseCase integrityUseCase) {
        this.settlementLedger = settlementLedger;
        this.reconciliationPort = reconciliationPort;
        this.integrityUseCase = integrityUseCase;
    }

    /** 영업일 하루 [00:00, 다음날 00:00) 대사 — EOD 배치가 전날을 대상으로 호출 */
    @Transactional
    public RunView reconcileDay(LocalDate businessDate) {
        return reconcile(businessDate, businessDate.atStartOfDay(), businessDate.plusDays(1).atStartOfDay());
    }

    /** 오늘 0시부터 지금까지 (시연·운영 중 수시 점검용) */
    @Transactional
    public RunView reconcileToday() {
        LocalDate today = LocalDate.now();
        return reconcile(today, today.atStartOfDay(), LocalDateTime.now().plusSeconds(1));
    }

    @Transactional
    public RunView reconcile(LocalDate businessDate, LocalDateTime from, LocalDateTime to) {
        long t0 = System.nanoTime();
        ReconciliationRun run = ReconciliationRun.start(businessDate, from, to);

        Totals bank = settlementLedger.bankTotals(from, to);
        Totals settlement = settlementLedger.settlementTotals(from, to);
        run.recordTotals(bank.count(), bank.amount(), settlement.count(), settlement.amount());

        long missing = settlementLedger.countBreaks(BreakKind.MISSING_IN_SETTLEMENT, from, to);
        long inFlight = settlementLedger.countBreaks(BreakKind.IN_FLIGHT, from, to);
        long unexpected = settlementLedger.countBreaks(BreakKind.UNEXPECTED_IN_SETTLEMENT, from, to);
        long mismatch = settlementLedger.countBreaks(BreakKind.AMOUNT_MISMATCH, from, to);
        run.recordBreakCounts(missing, inFlight, unexpected, mismatch, settlementLedger.countDuplicateDeliveries());

        // 조사 대상(진짜 차이)만 상세 기록. 전달 중인 건은 건수만 남긴다.
        int remaining = MAX_BREAK_ITEMS;
        for (BreakKind kind : List.of(BreakKind.MISSING_IN_SETTLEMENT, BreakKind.UNEXPECTED_IN_SETTLEMENT,
                BreakKind.AMOUNT_MISMATCH)) {
            if (remaining <= 0) break;
            for (SettlementLedgerPort.Break b : settlementLedger.findBreaks(kind, from, to, remaining)) {
                run.addBreak(b.transferId(), b.kind().name(), b.txType(), b.bankAmount(), b.settlementAmount());
                remaining--;
            }
        }

        boolean balanced = integrityUseCase.check().balanced();
        run.complete(balanced, (System.nanoTime() - t0) / 1_000_000);
        ReconciliationRun saved = reconciliationPort.save(run);

        log.info("[대사] {} {} — 은행 {}건 / 정산 {}건, 누락 {} · 전달중 {} · 불명 {} · 금액불일치 {} · 중복수신 {} · 원장무결성 {}",
                businessDate, saved.getStatus(), bank.count(), settlement.count(),
                missing, inFlight, unexpected, mismatch, saved.getDuplicateDeliveries(), balanced);
        return RunView.of(saved, true);
    }

    @Transactional(readOnly = true)
    public List<RunView> recent() {
        return reconciliationPort.findRecent().stream().map(r -> RunView.of(r, false)).toList();
    }

    @Transactional(readOnly = true)
    public RunView latest() {
        return reconciliationPort.findLatest().map(r -> RunView.of(r, true)).orElse(null);
    }
}
