package com.aifinance.application.port.out;

import com.aifinance.domain.ledger.EntryType;
import com.aifinance.domain.ledger.LedgerEntry;

import java.math.BigDecimal;
import java.util.Map;

public interface LedgerPort {
    LedgerEntry save(LedgerEntry ledgerEntry);
    BigDecimal sumByType(EntryType type);
    /** 계좌별 원장 잔액 (∑CREDIT - ∑DEBIT) */
    Map<Long, BigDecimal> ledgerBalanceByAccount();
    /** 분개가 정확히 2건이 아닌 거래 수 */
    long countTransfersWithoutTwoEntries();
    /** 무결성 값 일괄 조회 (단일 스냅샷) */
    IntegritySnapshot integritySnapshot();
    /** 한 계좌의 원장 잔액 (∑CREDIT - ∑DEBIT) — 잔액 재구성이 계좌 락 안에서 다시 계산할 때 쓴다 */
    BigDecimal ledgerBalanceOf(Long accountId);
    /** 시뮬레이션 초기화 전용 — 원장 불변성 예외. ArchitectureTest 가 이 이름 외의 삭제·수정 메서드를 금지한다. */
    void deleteAllForSimulationReset();

    record IntegritySnapshot(BigDecimal totalDebit, BigDecimal totalCredit, long transfersWithoutTwoEntries,
                             BigDecimal sumOfAllBalances, long mismatchedAccountCount) {}
}
