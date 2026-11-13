package com.aifinance.adapter.out.persistence;

import com.aifinance.application.port.out.ReconciliationPort;
import com.aifinance.domain.reconciliation.ReconciliationRun;
import com.aifinance.domain.reconciliation.ReconciliationRunRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class ReconciliationJpaAdapter implements ReconciliationPort {

    private final ReconciliationRunRepository repository;

    public ReconciliationJpaAdapter(ReconciliationRunRepository repository) {
        this.repository = repository;
    }

    @Override
    public ReconciliationRun save(ReconciliationRun run) {
        return repository.save(run);
    }

    @Override
    public List<ReconciliationRun> findRecent() {
        return repository.findTop20ByOrderByIdDesc();
    }

    @Override
    public Optional<ReconciliationRun> findLatest() {
        return repository.findTopByOrderByIdDesc();
    }

    @Override
    public void deleteAll() {
        // cascade 로 차이 내역(reconciliation_breaks)도 함께 삭제
        repository.deleteAll();
    }
}
