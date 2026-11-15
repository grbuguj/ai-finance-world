package com.aifinance.domain.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {

    List<ReconciliationRun> findTop20ByOrderByIdDesc();

    Optional<ReconciliationRun> findTopByOrderByIdDesc();
}
