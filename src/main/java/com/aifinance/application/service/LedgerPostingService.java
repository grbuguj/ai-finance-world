package com.aifinance.application.service;

import com.aifinance.application.event.TransactionPostedEvent;
import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.port.out.LedgerPort;
import com.aifinance.application.port.out.OutboxPort;
import com.aifinance.application.port.out.PostingMetricsPort;
import com.aifinance.application.port.out.TransferPort;
import com.aifinance.domain.account.Account;
import com.aifinance.domain.ledger.EntryType;
import com.aifinance.domain.ledger.LedgerEntry;
import com.aifinance.domain.outbox.OutboxEvent;
import com.aifinance.domain.transfer.TransactionType;
import com.aifinance.domain.transfer.Transfer;
import com.aifinance.domain.transfer.TransferStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 복식부기 기장 서비스 — 모든 거래(송금/소비/투자/환불/급여)가 이 한 곳을 거친다.
 *
 * 1) 계좌 ID 오름차순으로 비관적 락(SELECT ... FOR UPDATE) 획득 → 데드락 방지
 * 2) 잔액 변경 (에이전트 계좌는 잔액 초과 출금 불가)
 * 3) 거래(Transfer) 1건 + 원장 DEBIT 1건 + CREDIT 1건을 같은 트랜잭션에 기록
 * 4) 외부 전달용 이벤트를 Outbox 테이블에 같은 트랜잭션으로 기록 (커밋되면 반드시 언젠가 발행된다)
 * 5) 커밋 후 잔액 캐시 갱신용 애플리케이션 이벤트 발행
 *
 * 반드시 호출자가 연 트랜잭션 안에서 실행되어야 한다 (MANDATORY).
 */
@Service
public class LedgerPostingService {

    private final AccountPort accountPort;
    private final LedgerPort ledgerPort;
    private final TransferPort transferPort;
    private final ApplicationEventPublisher eventPublisher;
    private final PostingMetricsPort metrics;
    private final OutboxPort outboxPort;

    public LedgerPostingService(AccountPort accountPort, LedgerPort ledgerPort, TransferPort transferPort,
                                ApplicationEventPublisher eventPublisher, PostingMetricsPort metrics,
                                OutboxPort outboxPort) {
        this.accountPort = accountPort;
        this.ledgerPort = ledgerPort;
        this.transferPort = transferPort;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.outboxPort = outboxPort;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Transfer post(Long fromAccountId, Long toAccountId, BigDecimal amount,
                         TransactionType txType, String description, Long originalTransferId) {
        long start = System.nanoTime();
        try {
            Transfer transfer = doPost(fromAccountId, toAccountId, amount, txType, description, originalTransferId);
            metrics.recordPosted(txType, System.nanoTime() - start, lockWaitNanos.get());
            return transfer;
        } catch (RuntimeException e) {
            metrics.recordFailed(txType, e.getClass().getSimpleName());
            throw e;
        } finally {
            lockWaitNanos.remove();
        }
    }

    // 현재 스레드의 이번 기장에서 행 락을 기다린 시간
    private final ThreadLocal<Long> lockWaitNanos = ThreadLocal.withInitial(() -> 0L);

    private Transfer doPost(Long fromAccountId, Long toAccountId, BigDecimal amount,
                            TransactionType txType, String description, Long originalTransferId) {

        // 1. 입력 검증
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("거래 금액은 0보다 커야 합니다");
        }
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("같은 계좌로 송금 불가");
        }

        // 2. 락 순서 결정 - 계좌 ID가 더 작은 것을 먼저 Lock
        // (A→B, B→A 가 동시에 들어와도 둘 다 작은 ID부터 잠그므로 서로를 무한히 기다리지 않는다)
        Account fromAccount;
        Account toAccount;
        if (fromAccountId < toAccountId) {
            fromAccount = lock(fromAccountId);
            toAccount = lock(toAccountId);
        } else {
            toAccount = lock(toAccountId);
            fromAccount = lock(fromAccountId);
        }

        // 3. 잔액 변경 (잔액 부족이면 InsufficientBalanceException → 트랜잭션 전체 롤백)
        fromAccount.withdraw(amount);
        toAccount.deposit(amount);
        accountPort.save(fromAccount);
        accountPort.save(toAccount);

        // 4. 거래 이력 저장
        Transfer transfer = transferPort.save(Transfer.builder()
                .sender(fromAccount)
                .receiver(toAccount)
                .amount(amount)
                .type(TransferStatus.SUCCESS)
                .txType(txType)
                .description(description)
                .originalTransferId(originalTransferId)
                .build());

        // 5. 원장 분개 2건 (같은 transfer_id)
        ledgerPort.save(LedgerEntry.builder()
                .account(fromAccount)
                .transfer(transfer)
                .type(EntryType.DEBIT)
                .amount(amount)
                .build());
        ledgerPort.save(LedgerEntry.builder()
                .account(toAccount)
                .transfer(transfer)
                .type(EntryType.CREDIT)
                .amount(amount)
                .build());

        // 6. 외부(정산 시스템)로 나갈 이벤트를 Outbox 에 기록 — 거래와 같은 트랜잭션
        //    여기서 바로 Kafka 로 보내지 않는다: 커밋 전에 보내면 롤백된 거래가 나가고,
        //    커밋 후에 보내면 그 사이 장애로 유실된다. Outbox 는 둘 다 막는다.
        TransactionPostedEvent event = new TransactionPostedEvent(
                transfer.getId(),
                txType.name(),
                fromAccount.getId(), fromAccount.getName(),
                toAccount.getId(), toAccount.getName(),
                amount,
                fromAccount.getBalance(),
                toAccount.getBalance(),
                description,
                transfer.getCreatedAt() != null ? transfer.getCreatedAt() : LocalDateTime.now()
        );
        outboxPort.save(OutboxEvent.pending(
                "TRANSFER", transfer.getId(), "TRANSACTION_POSTED",
                String.valueOf(fromAccount.getId()), event.toJson(), LocalDateTime.now()));

        // 7. 커밋 후 잔액 캐시 갱신 (보조 수단 — 실패해도 DB 가 진실)
        eventPublisher.publishEvent(event);

        return transfer;
    }

    private Account lock(Long accountId) {
        long t0 = System.nanoTime();
        Account account = accountPort.findByIdWithLock(accountId)
                .orElseThrow(() -> new IllegalArgumentException("계좌 없음: " + accountId));
        lockWaitNanos.set(lockWaitNanos.get() + (System.nanoTime() - t0));
        return account;
    }
}
