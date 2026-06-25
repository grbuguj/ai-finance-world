package com.aifinance.application.port.out;

import com.aifinance.domain.transfer.Transfer;

import java.math.BigDecimal;
import java.util.List;

public interface TransferPort {
    Transfer save(Transfer transfer);
    List<Transfer> findAll();
    List<Transfer> findRecent();
    List<Transfer> findUndescribed();
    long count();
    BigDecimal sumAmount();
    void deleteAll();
}
