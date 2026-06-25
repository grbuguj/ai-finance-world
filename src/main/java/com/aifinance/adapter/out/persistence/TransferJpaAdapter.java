package com.aifinance.adapter.out.persistence;

import com.aifinance.application.port.out.TransferPort;
import com.aifinance.domain.transfer.Transfer;
import com.aifinance.domain.transfer.TransferRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class TransferJpaAdapter implements TransferPort {

    private final TransferRepository transferRepository;

    public TransferJpaAdapter( TransferRepository transferRepository) {
        this.transferRepository = transferRepository;
    }

    @Override
    public Transfer save(Transfer transfer) {
        return transferRepository.save(transfer);
    }

    @Override
    public List<Transfer> findAll() {
        return transferRepository.findAll();
    }

    @Override
    public List<Transfer> findRecent() {
        return transferRepository.findTop20ByOrderByIdDesc();
    }

    @Override
    public List<Transfer> findUndescribed() {
        return transferRepository.findTop3ByDescriptionIsNullOrderByIdDesc();
    }

    @Override
    public long count() {
        return transferRepository.count();
    }

    @Override
    public BigDecimal sumAmount() {
        return transferRepository.sumAmount();
    }

    @Override
    public void deleteAll() {
        transferRepository.deleteAll();
    }
}
