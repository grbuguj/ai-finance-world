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
    void deleteAll();

    record IntegritySnapshot(BigDecimal totalDebit, BigDecimal totalCredit, long transfersWithoutTwoEntries,
                             BigDecimal sumOfAllBalances, long mismatchedAccountCount) {}
}
