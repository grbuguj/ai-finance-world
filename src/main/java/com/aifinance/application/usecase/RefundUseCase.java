package com.aifinance.application.usecase;

import com.aifinance.application.port.out.TransferPort;
import com.aifinance.application.service.AccountLockExecutor;
import com.aifinance.application.service.LedgerPostingService;
import com.aifinance.domain.transfer.TransactionType;
import com.aifinance.domain.transfer.Transfer;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 환불: 소비(SPEND) 거래의 역거래 (가맹점 → 에이전트).
 *
 * 원거래 행을 비관적 락으로 잡은 뒤 refunded 플래그를 확인하므로,
 * 같은 소비 건에 환불 요청이 동시에 여러 번 들어와도 정확히 1번만 환불된다.
 * 원거래는 수정/삭제하지 않고 새 거래를 추가해 원장을 보정한다 (원장 불변성).
 */
@Service
public class RefundUseCase {

    private final TransferPort transferPort;
    private final AccountLockExecutor lockExecutor;
    private final LedgerPostingService postingService;

    public RefundUseCase(TransferPort transferPort, AccountLockExecutor lockExecutor, LedgerPostingService postingService) {
        this.transferPort = transferPort;
        this.lockExecutor = lockExecutor;
        this.postingService = postingService;
    }

    public Transfer refund(Long spendTransferId) {
        // 락을 걸 계좌를 알기 위한 사전 조회 (검증은 락 획득 후 다시 한다)
        Transfer peek = transferPort.findById(spendTransferId)
                .orElseThrow(() -> new IllegalArgumentException("거래 없음: " + spendTransferId));
        Long agentAccountId = peek.getSender().getId();
        Long merchantAccountId = peek.getReceiver().getId();

        return lockExecutor.execute(List.of(agentAccountId, merchantAccountId), () -> {
            Transfer original = transferPort.findByIdWithLock(spendTransferId)
                    .orElseThrow(() -> new IllegalArgumentException("거래 없음: " + spendTransferId));
            original.markRefunded(); // SPEND 가 아니거나 이미 환불됐으면 예외 → 롤백
            transferPort.save(original);

            String description = "환불 · " + (original.getDescription() == null ? "" : original.getDescription());
            return postingService.post(merchantAccountId, agentAccountId, original.getAmount(),
                    TransactionType.REFUND, description, original.getId());
        });
    }

    /** 해당 계좌의 가장 최근 미환불 소비 건을 환불. 대상이 없으면 null. */
    public Transfer refundLatest(Long agentAccountId) {
        List<Transfer> candidates = transferPort.findRefundableSpends(agentAccountId);
        if (candidates.isEmpty()) {
            return null;
        }
        return refund(candidates.get(0).getId());
    }
}
