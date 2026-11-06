package com.aifinance.adapter.out.persistence;

import com.aifinance.application.port.out.TransferPort;
import com.aifinance.domain.transfer.TransactionType;
import com.aifinance.domain.transfer.Transfer;
import com.aifinance.domain.transfer.TransferRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class TransferJpaAdapter implements TransferPort {

    private final TransferRepository transferRepository;

    public TransferJpaAdapter(TransferRepository transferRepository) {
        this.transferRepository = transferRepository;
    }

    @Override
    public Transfer save(Transfer transfer) {
        return transferRepository.save(transfer);
    }

    @Override
    public Optional<Transfer> findById(Long id) {
        return transferRepository.findById(id);
    }

    @Override
    public Optional<Transfer> findByIdWithLock(Long id) {
        return transferRepository.findByIdWithLock(id);
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
    public List<Transfer> findRefundableSpends(Long accountId) {
        return transferRepository.findTop5BySenderIdAndTxTypeAndRefundedFalseOrderByIdDesc(accountId, TransactionType.SPEND);
    }

    @Override
    public long count() {
        return transferRepository.countExcludingOpening();
    }

    @Override
    public BigDecimal sumAmount() {
        return transferRepository.sumAmount();
    }

    @Override
    public Map<TransactionType, Long> countByType() {
        Map<TransactionType, Long> result = new EnumMap<>(TransactionType.class);
        for (Object[] row : transferRepository.countByTxType()) {
            result.put((TransactionType) row[0], (Long) row[1]);
        }
        return result;
    }

    @Override
    public void deleteAll() {
        transferRepository.deleteAllInBatch();
    }
}
