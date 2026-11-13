package com.aifinance.adapter.out.persistence;

import com.aifinance.application.port.out.OutboxPort;
import com.aifinance.domain.outbox.OutboxEvent;
import com.aifinance.domain.outbox.OutboxRepository;
import com.aifinance.domain.outbox.OutboxStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class OutboxJpaAdapter implements OutboxPort {

    private final OutboxRepository repository;

    public OutboxJpaAdapter(OutboxRepository repository) {
        this.repository = repository;
    }

    @Override
    public OutboxEvent save(OutboxEvent event) {
        return repository.save(event);
    }

    @Override
    public void saveAll(List<OutboxEvent> events) {
        repository.saveAll(events);
    }

    @Override
    public List<OutboxEvent> findPublishable(LocalDateTime now, int limit) {
        return repository.findPublishable(OutboxStatus.PENDING, now, PageRequest.of(0, limit));
    }

    @Override
    public long countByStatus(OutboxStatus status) {
        return repository.countByStatus(status);
    }

    @Override
    public Optional<LocalDateTime> oldestPendingCreatedAt() {
        return Optional.ofNullable(repository.oldestCreatedAt(OutboxStatus.PENDING));
    }

    @Override
    public List<OutboxEvent> findDead() {
        return repository.findByStatusOrderByIdAsc(OutboxStatus.DEAD);
    }

    @Override
    @Transactional
    public int deletePublishedBefore(LocalDateTime before, int batchSize) {
        List<Long> ids = repository.findIdsPublishedBefore(OutboxStatus.PUBLISHED, before, PageRequest.of(0, batchSize));
        if (!ids.isEmpty()) {
            repository.deleteAllByIdInBatch(ids);
        }
        return ids.size();
    }

    @Override
    public void deleteAll() {
        repository.deleteAllInBatch();
    }
}
