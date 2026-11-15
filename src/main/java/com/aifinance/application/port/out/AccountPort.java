package com.aifinance.application.port.out;

import com.aifinance.domain.account.Account;
import com.aifinance.domain.account.AccountType;

import java.util.List;
import java.util.Optional;


public interface AccountPort {
    Optional<Account> findByIdWithLock(Long id);
    Optional<Account> findById(Long id);
    Account save(Account account);
    List<Account> findAll();
    List<Account> findByType(AccountType type);
}
