package com.aifinance.adapter.out.persistence;

import com.aifinance.application.port.out.AccountPort;
import com.aifinance.domain.account.Account;
import com.aifinance.domain.account.AccountRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;


@Component
public class AccountJpaAdapter implements AccountPort {

    private final AccountRepository accountRepository;

    public AccountJpaAdapter(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }



    @Override
    public Optional<Account> findByIdWithLock(Long Id ) {
        return accountRepository.findByIdWithLock(Id);

    }

    @Override
    public Account save(Account account) {
        return accountRepository.save(account);

    }

    @Override
    public List<Account> findAll() {
        return accountRepository.findAll();
    }

}
