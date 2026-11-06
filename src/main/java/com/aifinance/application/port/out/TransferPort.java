package com.aifinance.application.port.out;

import com.aifinance.domain.transfer.TransactionType;
import com.aifinance.domain.transfer.Transfer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface TransferPort {
    Transfer save(Transfer transfer);
    Optional<Transfer> findById(Long id);
    Optional<Transfer> findByIdWithLock(Long id);
    List<Transfer> findAll();
    List<Transfer> findRecent();
    List<Transfer> findUndescribed();
    List<Transfer> findRefundableSpends(Long accountId);
    long count();
    BigDecimal sumAmount();
    Map<TransactionType, Long> countByType();
    void deleteAll();
}
