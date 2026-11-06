package com.aifinance.adapter.out.persistence;

import com.aifinance.application.port.out.LedgerPort;
import com.aifinance.domain.ledger.EntryType;
import com.aifinance.domain.ledger.LedgerEntry;
import com.aifinance.domain.ledger.LedgerRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Component
public class LedgerJpaAdapter implements LedgerPort {

    private final LedgerRepository ledgerRepository;

    public LedgerJpaAdapter(LedgerRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
    }

    @Override
    public LedgerEntry save(LedgerEntry ledgerEntry) {
        return ledgerRepository.save(ledgerEntry);
    }

    @Override
    public BigDecimal sumByType(EntryType type) {
        return ledgerRepository.sumByType(type);
    }

    @Override
    public Map<Long, BigDecimal> ledgerBalanceByAccount() {
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Object[] row : ledgerRepository.sumGroupByAccountAndType()) {
            Long accountId = (Long) row[0];
            EntryType type = (EntryType) row[1];
            BigDecimal sum = (BigDecimal) row[2];
            BigDecimal signed = type == EntryType.CREDIT ? sum : sum.negate();
            result.merge(accountId, signed, BigDecimal::add);
        }
        return result;
    }

    @Override
    public long countTransfersWithoutTwoEntries() {
        return ledgerRepository.countTransfersWithoutTwoEntries();
    }

    @Override
    public IntegritySnapshot integritySnapshot() {
        Object[] row = ledgerRepository.integritySnapshot().get(0);
        return new IntegritySnapshot(
                toBigDecimal(row[0]), toBigDecimal(row[1]),
                ((Number) row[2]).longValue(),
                toBigDecimal(row[3]),
                ((Number) row[4]).longValue());
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o instanceof BigDecimal b) return b;
        return new BigDecimal(String.valueOf(o));
    }

    @Override
    public void deleteAll() {
        ledgerRepository.deleteAllInBatch();
    }
}
