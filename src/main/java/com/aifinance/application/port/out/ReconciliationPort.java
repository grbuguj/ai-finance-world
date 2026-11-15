package com.aifinance.application.port.out;

import com.aifinance.domain.reconciliation.ReconciliationRun;

import java.util.List;
import java.util.Optional;

public interface ReconciliationPort {
    ReconciliationRun save(ReconciliationRun run);
    List<ReconciliationRun> findRecent();
    Optional<ReconciliationRun> findLatest();
    void deleteAll();
}
