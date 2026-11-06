package com.aifinance.adapter.out.persistence;

import com.aifinance.application.port.out.IdempotencyPort;
import com.aifinance.domain.idempotency.DuplicateIdempotencyKeyException;
import com.aifinance.domain.idempotency.IdempotencyRecord;
import com.aifinance.domain.idempotency.IdempotencyRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Component
public class IdempotencyJpaAdapter implements IdempotencyPort {

    private final IdempotencyRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    public IdempotencyJpaAdapter(IdempotencyRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        return repository.findById(key);
    }

    /**
     * save() 는 PK 가 이미 있으면 UPDATE(merge) 해버리므로 persist + flush 로 반드시 INSERT 를 실행한다.
     * 동시에 같은 키가 들어오면 DB 가 두 번째 INSERT 를 막는다 (InnoDB: 첫 트랜잭션 커밋까지 대기 후 중복 키 오류).
     */
    @Override
    public IdempotencyRecord insert(String key, String requestHash) {
        IdempotencyRecord record = new IdempotencyRecord(key, requestHash);
        try {
            entityManager.persist(record);
            entityManager.flush();
            return record;
        } catch (PersistenceException e) {
            throw new DuplicateIdempotencyKeyException(key, e);
        }
    }

    @Override
    @Transactional
    public int deleteOlderThan(LocalDateTime before) {
        return repository.deleteOlderThan(before);
    }

    @Override
    public void deleteAll() {
        repository.deleteAllInBatch();
    }
}
