package com.aifinance.application.usecase;


import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.port.out.LedgerPort;
import com.aifinance.application.port.out.TransferPort;
import com.aifinance.domain.account.Account;
import com.aifinance.domain.ledger.EntryType;
import com.aifinance.domain.ledger.LedgerEntry;
import com.aifinance.domain.transfer.Transfer;
import com.aifinance.domain.transfer.TransferStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class TransferUseCase {

    private final AccountPort accountPort;
    private final LedgerPort ledgerPort;
    private final TransferPort transferPort;

    public TransferUseCase(AccountPort accountPort, LedgerPort ledgerPort, TransferPort transferPort) {
        this.accountPort = accountPort;
        this.ledgerPort = ledgerPort;
        this.transferPort = transferPort;
    }

    @Transactional
    public Transfer transfer(Long fromAccountId, Long toAccountId, BigDecimal amount, String description) {

        Account first;
        Account second;
        Account fromAccount;
        Account toAccount;


        // 1. 같은 계좌인지 체크 먼저
        if (fromAccountId.equals(toAccountId)) {
            throw new RuntimeException("같은 계좌로 송금 불가");
        }

        // 2. 락 순서 결정 -  계좌 Id가 더 작은걸 먼저 Lock!
        // ( 데드락 방지. A와 B가 동시에 서로에게 송금한다면 서로를 무한히 대기할 수 있음. 작은 계좌부터 Lock)
        if (fromAccountId < toAccountId) {

            first = accountPort.findByIdWithLock(fromAccountId)
                    .orElseThrow(() -> new
                            RuntimeException("계좌 없음"));
            second = accountPort.findByIdWithLock(toAccountId)
                    .orElseThrow(() -> new
                            RuntimeException("계좌 없음"));

            fromAccount = first;
            toAccount = second;

        } else {
            first = accountPort.findByIdWithLock(toAccountId)
                    .orElseThrow(() -> new
                            RuntimeException("계좌 없음"));

            second = accountPort.findByIdWithLock(fromAccountId)
                    .orElseThrow(() -> new
                            RuntimeException("계좌 없음"));

            fromAccount = second;
            toAccount = first;
        }

        // 3. 잔액 확인
        if (fromAccount.getBalance().compareTo(amount) < 0) {
            throw new RuntimeException("잔액 부족");
        }

        // 4. 송금로직 실행
        fromAccount.withdraw(amount);
        toAccount.deposit(amount);
        accountPort.save(fromAccount);
        accountPort.save(toAccount);

        // 5. LedgerEntry 저장
        LedgerEntry debitEntry = LedgerEntry.builder()
                .account(fromAccount)
                .type(EntryType.DEBIT)
                .amount(amount)
                .build();

        LedgerEntry creditEntry = LedgerEntry.builder()
                .account(toAccount)
                .type(EntryType.CREDIT)
                .amount(amount)
                .build();

        ledgerPort.save(debitEntry);
        ledgerPort.save(creditEntry);

        // 6. Transfer 저장
        Transfer transfer = Transfer.builder()
                .sender(fromAccount)
                .receiver(toAccount)
                .amount(amount)
                .type(TransferStatus.SUCCESS)
                .description(description)
                .build();

        return transferPort.save(transfer);
    }

}
