package com.aifinance.domain.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {

    @Modifying
    @Query("delete from IdempotencyRecord r where r.createdAt < :before")
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
