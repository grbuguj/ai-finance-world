package com.aifinance.domain.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /** 발행 대상: PENDING 이고 재시도 시각이 지난 행을 id 순으로 (idx_outbox_status_next 사용) */
    @Query("select o from OutboxEvent o where o.status = :status and o.nextAttemptAt <= :now order by o.id asc")
    List<OutboxEvent> findPublishable(@Param("status") OutboxStatus status, @Param("now") LocalDateTime now,
                                      Pageable pageable);

    long countByStatus(OutboxStatus status);

    @Query("select min(o.createdAt) from OutboxEvent o where o.status = :status")
    LocalDateTime oldestCreatedAt(@Param("status") OutboxStatus status);

    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxStatus status);
}
