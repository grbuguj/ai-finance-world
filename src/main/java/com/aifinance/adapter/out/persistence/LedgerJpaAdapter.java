package com.aifinance.adapter.out.persistence;

import com.aifinance.application.port.out.LedgerPort;
import com.aifinance.domain.ledger.EntryType;
import com.aifinance.domain.ledger.LedgerEntry;
import com.aifinance.domain.ledger.LedgerRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

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
    public void deleteAll() {
        ledgerRepository.deleteAll();
    }
}
