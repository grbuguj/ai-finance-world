package com.aifinance.application.usecase;

import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.port.out.BalanceCachePort;
import com.aifinance.application.port.out.LedgerPort;
import com.aifinance.application.service.AccountLockExecutor;
import com.aifinance.domain.account.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 잔액 재구성 — 잔액(accounts.balance)은 원장에서 언제든 다시 만들 수 있는 파생값이라는 주장을 실제 도구로 만든 것.
 *
 *  - dry-run(기본): 원장 재계산 값(∑CREDIT - ∑DEBIT)과 현재 잔액이 다른 계좌 목록만 돌려준다 (아무것도 바꾸지 않는다)
 *  - apply: 불일치 계좌를 ID 오름차순으로, 계좌별 락 안에서 원장 값을 "다시 계산해" 교정한다.
 *           (락 전에 구한 값이 아니라 락 안에서 다시 구하므로, 그 사이 들어온 거래가 있어도 틀리게 덮어쓰지 않는다)
 *           교정 후 잔액 캐시를 비우고, 교정 내역을 로그와 응답에 남긴다.
 */
@Service
public class BalanceRebuildUseCase {

    private static final Logger log = LoggerFactory.getLogger(BalanceRebuildUseCase.class);

    public record BalanceDiff(Long accountId, String name, BigDecimal currentBalance, BigDecimal ledgerBalance, BigDecimal difference) {}

    /** @param diffs dry-run 이면 발견한 차이, apply 면 실제로 교정한 내역 */
    public record RebuildResult(boolean applied, int mismatchedFound, int corrected, List<BalanceDiff> diffs) {}

    private final AccountPort accountPort;
    private final LedgerPort ledgerPort;
    private final AccountLockExecutor lockExecutor;
    private final BalanceCachePort balanceCachePort;

    public BalanceRebuildUseCase(AccountPort accountPort, LedgerPort ledgerPort, AccountLockExecutor lockExecutor,
                                 BalanceCachePort balanceCachePort) {
        this.accountPort = accountPort;
        this.ledgerPort = ledgerPort;
        this.lockExecutor = lockExecutor;
        this.balanceCachePort = balanceCachePort;
    }

    public RebuildResult rebuild(boolean apply) {
        List<BalanceDiff> found = findDiffs();
        if (!apply) {
            return new RebuildResult(false, found.size(), 0, found);
        }
        List<BalanceDiff> corrected = new ArrayList<>();
        for (BalanceDiff d : found) { // ID 오름차순
            BalanceDiff done = lockExecutor.execute(List.of(d.accountId()), () -> {
                Account account = accountPort.findByIdWithLock(d.accountId()).orElseThrow();
                BigDecimal ledger = ledgerPort.ledgerBalanceOf(account.getId());
                if (account.getBalance().compareTo(ledger) == 0) {
                    return null; // 그 사이 이미 맞아졌다
                }
                BalanceDiff fix = new BalanceDiff(account.getId(), account.getName(), account.getBalance(), ledger,
                        ledger.subtract(account.getBalance()));
                account.correctBalanceTo(ledger);
                accountPort.save(account);
                log.warn("[잔액 재구성] 계좌 {}({}) 잔액 {} → 원장 기준 {} (차이 {})",
                        fix.accountId(), fix.name(), fix.currentBalance(), fix.ledgerBalance(), fix.difference());
                return fix;
            });
            if (done != null) {
                corrected.add(done);
            }
        }
        balanceCachePort.evictAll();
        return new RebuildResult(true, found.size(), corrected.size(), corrected);
    }

    private List<BalanceDiff> findDiffs() {
        Map<Long, BigDecimal> ledger = ledgerPort.ledgerBalanceByAccount();
        List<Account> accounts = new ArrayList<>(accountPort.findAll());
        accounts.sort(Comparator.comparing(Account::getId));
        List<BalanceDiff> diffs = new ArrayList<>();
        for (Account a : accounts) {
            BigDecimal expected = ledger.getOrDefault(a.getId(), BigDecimal.ZERO);
            if (a.getBalance().compareTo(expected) != 0) {
                diffs.add(new BalanceDiff(a.getId(), a.getName(), a.getBalance(), expected, expected.subtract(a.getBalance())));
            }
        }
        return diffs;
    }
}
