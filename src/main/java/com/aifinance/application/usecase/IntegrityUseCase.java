package com.aifinance.application.usecase;

import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.port.out.LedgerPort;
import com.aifinance.domain.account.Account;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 복식부기 무결성 검증. 아래 4가지가 모두 성립해야 balanced = true.
 *
 *  1) ∑DEBIT = ∑CREDIT                          (원장 전체 차변/대변 일치)
 *  2) 모든 거래는 분개를 정확히 2건 가진다          (거래 단위 대응)
 *  3) 모든 계좌: 잔액 = ∑CREDIT - ∑DEBIT          (잔액 필드와 원장 재계산 값 일치 → Lost Update 탐지)
 *  4) 모든 계좌(시스템 계좌 포함) 잔액 합 = 0       (닫힌 시스템 - 돈이 새거나 생기지 않음)
 */
@Service
public class IntegrityUseCase {

    private final AccountPort accountPort;
    private final LedgerPort ledgerPort;

    public IntegrityUseCase(AccountPort accountPort, LedgerPort ledgerPort) {
        this.accountPort = accountPort;
        this.ledgerPort = ledgerPort;
    }

    public record AccountMismatch(Long accountId, String name, BigDecimal balance, BigDecimal ledgerBalance) {}

    public record IntegrityReport(
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            boolean debitEqualsCredit,
            long transfersWithoutTwoEntries,
            long mismatchedAccountCount,
            List<AccountMismatch> mismatchedAccounts,
            BigDecimal sumOfAllBalances,
            boolean balanced
    ) {}

    @Transactional(readOnly = true)
    public IntegrityReport check() {
        // 1) 핵심 수치는 단일 SQL 로 → 시뮬레이션이 돌아가는 중에도 일관된 스냅샷
        LedgerPort.IntegritySnapshot snap = ledgerPort.integritySnapshot();

        // 2) 불일치 계좌가 있을 때만 상세 목록 조회 (진단용)
        List<AccountMismatch> mismatches = new ArrayList<>();
        if (snap.mismatchedAccountCount() > 0) {
            Map<Long, BigDecimal> ledgerBalances = ledgerPort.ledgerBalanceByAccount();
            for (Account account : accountPort.findAll()) {
                BigDecimal ledgerBalance = ledgerBalances.getOrDefault(account.getId(), BigDecimal.ZERO);
                if (account.getBalance().compareTo(ledgerBalance) != 0) {
                    mismatches.add(new AccountMismatch(account.getId(), account.getName(), account.getBalance(), ledgerBalance));
                }
            }
        }

        boolean debitEqualsCredit = snap.totalDebit().compareTo(snap.totalCredit()) == 0;
        boolean balanced = debitEqualsCredit
                && snap.transfersWithoutTwoEntries() == 0
                && snap.mismatchedAccountCount() == 0
                && snap.sumOfAllBalances().signum() == 0;
        return new IntegrityReport(snap.totalDebit(), snap.totalCredit(), debitEqualsCredit,
                snap.transfersWithoutTwoEntries(), snap.mismatchedAccountCount(), mismatches,
                snap.sumOfAllBalances(), balanced);
    }
}
