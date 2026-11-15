package com.aifinance.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 외부 정산 시스템 장부 조회 포트 — 대사(Reconciliation)용.
 * 코어는 정산 시스템의 테이블 구조를 모르고, 이 포트로 "비교 결과"만 받는다.
 */
public interface SettlementLedgerPort {

    enum BreakKind {
        /** 은행에는 있는데 정산 장부에 없음 (Outbox 도 이미 발행 처리됨 → 진짜 유실) */
        MISSING_IN_SETTLEMENT,
        /** 은행에는 있고 정산 장부에는 아직 없지만 Outbox 에 PENDING 으로 남아 있음 → 전달 중, 차이 아님 */
        IN_FLIGHT,
        /** 정산 장부에만 있는 거래 (은행 원장에 없는 id) */
        UNEXPECTED_IN_SETTLEMENT,
        /** 양쪽에 다 있지만 금액이 다름 */
        AMOUNT_MISMATCH
    }

    record Break(Long transferId, BreakKind kind, String txType, BigDecimal bankAmount, BigDecimal settlementAmount) {}

    record Totals(long count, BigDecimal amount) {}

    Totals bankTotals(LocalDateTime from, LocalDateTime to);

    Totals settlementTotals(LocalDateTime from, LocalDateTime to);

    long countBreaks(BreakKind kind, LocalDateTime from, LocalDateTime to);

    List<Break> findBreaks(BreakKind kind, LocalDateTime from, LocalDateTime to, int limit);

    /** 중복 도착했던 메시지 수 (멱등 소비자가 걸러낸 수) */
    long countDuplicateDeliveries();

    void deleteAll();
}
