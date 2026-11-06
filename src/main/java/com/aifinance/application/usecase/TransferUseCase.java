package com.aifinance.application.usecase;

import com.aifinance.application.service.AccountLockExecutor;
import com.aifinance.application.service.IdempotentExecutor;
import com.aifinance.application.service.LedgerPostingService;
import com.aifinance.domain.transfer.TransactionType;
import com.aifinance.domain.transfer.Transfer;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 에이전트 간 송금.
 * 분산 락(선택) → 트랜잭션 → 비관적 락(ID 오름차순) → 복식부기 기장 순으로 처리된다.
 */
@Service
public class TransferUseCase {

    private final AccountLockExecutor lockExecutor;
    private final LedgerPostingService postingService;
    private final IdempotentExecutor idempotentExecutor;

    public TransferUseCase(AccountLockExecutor lockExecutor, LedgerPostingService postingService,
                           IdempotentExecutor idempotentExecutor) {
        this.lockExecutor = lockExecutor;
        this.postingService = postingService;
        this.idempotentExecutor = idempotentExecutor;
    }

    /** 멱등성 키를 받는 송금. 같은 키로 재요청하면 새로 송금하지 않고 첫 결과를 돌려준다. */
    public IdempotentExecutor.Result transfer(String idempotencyKey, Long fromAccountId, Long toAccountId,
                                              BigDecimal amount, String description) {
        String hash = IdempotentExecutor.hash("TRANSFER", fromAccountId, toAccountId, amount);
        return idempotentExecutor.execute(idempotencyKey, hash, List.of(fromAccountId, toAccountId), () ->
                postingService.post(fromAccountId, toAccountId, amount, TransactionType.TRANSFER, description, null));
    }

    public Transfer transfer(Long fromAccountId, Long toAccountId, BigDecimal amount, String description) {
        return lockExecutor.execute(List.of(fromAccountId, toAccountId), () ->
                postingService.post(fromAccountId, toAccountId, amount, TransactionType.TRANSFER, description, null));
    }
}
