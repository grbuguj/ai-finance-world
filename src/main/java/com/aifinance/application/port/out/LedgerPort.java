package com.aifinance.application.port.out;

import com.aifinance.domain.ledger.EntryType;
import com.aifinance.domain.ledger.LedgerEntry;

import java.math.BigDecimal;

public interface LedgerPort {
    LedgerEntry save(LedgerEntry ledgerEntry);
    BigDecimal sumByType(EntryType type);
    void deleteAll();
}
