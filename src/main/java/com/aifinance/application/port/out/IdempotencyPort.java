package com.aifinance.application.port.out;

import com.aifinance.domain.idempotency.IdempotencyRecord;

import java.time.LocalDateTime;
import java.util.Optional;

public interface IdempotencyPort {
    Optional<IdempotencyRecord> find(String key);

    /**
     * 키를 즉시 INSERT 한다 (현재 트랜잭션 안에서 flush).
     * 같은 키가 이미 있거나 다른 트랜잭션이 같은 키를 쓰는 중이면 DuplicateIdempotencyKeyException.
     */
    IdempotencyRecord insert(String key, String requestHash);

    int deleteOlderThan(LocalDateTime before);

    void deleteAll();
}
