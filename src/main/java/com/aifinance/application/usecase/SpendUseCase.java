package com.aifinance.application.usecase;

import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.service.AccountLockExecutor;
import com.aifinance.application.service.IdempotentExecutor;
import com.aifinance.application.service.LedgerPostingService;
import com.aifinance.domain.account.Account;
import com.aifinance.domain.account.AccountType;
import com.aifinance.domain.transfer.TransactionType;
import com.aifinance.domain.transfer.Transfer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 소비: 에이전트 → 외부 가맹점 결제.
 * 가맹점도 원장상 하나의 계좌이므로, 돈이 은행 밖으로 나가도 DEBIT/CREDIT 이 항상 짝을 이룬다.
 */
@Service
public class SpendUseCase {

    private final AccountPort accountPort;
    private final AccountLockExecutor lockExecutor;
    private final LedgerPostingService postingService;
    private final IdempotentExecutor idempotentExecutor;

    public SpendUseCase(AccountPort accountPort, AccountLockExecutor lockExecutor, LedgerPostingService postingService,
                        IdempotentExecutor idempotentExecutor) {
        this.accountPort = accountPort;
        this.lockExecutor = lockExecutor;
        this.postingService = postingService;
        this.idempotentExecutor = idempotentExecutor;
    }

    public Transfer spend(Long agentAccountId, Long merchantAccountId, BigDecimal amount, String description) {
        return spend(null, agentAccountId, merchantAccountId, amount, description).transfer();
    }

    /** 멱등성 키를 받는 결제. 같은 키로 재요청하면 새로 결제하지 않고 첫 결과를 돌려준다. */
    public IdempotentExecutor.Result spend(String idempotencyKey, Long agentAccountId, Long merchantAccountId,
                                           BigDecimal amount, String description) {
        Account merchant = accountPort.findById(merchantAccountId)
                .orElseThrow(() -> new IllegalArgumentException("가맹점 없음: " + merchantAccountId));
        if (merchant.getAccountType() != AccountType.MERCHANT) {
            throw new IllegalArgumentException("가맹점 계좌가 아닙니다: " + merchantAccountId);
        }
        String hash = IdempotentExecutor.hash("SPEND", agentAccountId, merchantAccountId, amount);
        return idempotentExecutor.execute(idempotencyKey, hash, List.of(agentAccountId, merchantAccountId), () ->
                postingService.post(agentAccountId, merchantAccountId, amount, TransactionType.SPEND, description, null));
    }

    public List<Account> merchants() {
        return accountPort.findByType(AccountType.MERCHANT);
    }
}
