package com.aifinance.application.port.out;

import com.aifinance.domain.outbox.OutboxEvent;
import com.aifinance.domain.outbox.OutboxStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OutboxPort {
    /** 반드시 거래와 같은 트랜잭션 안에서 호출된다 */
    OutboxEvent save(OutboxEvent event);

    void saveAll(List<OutboxEvent> events);

    List<OutboxEvent> findPublishable(LocalDateTime now, int limit);

    long countByStatus(OutboxStatus status);

    /** 가장 오래된 PENDING 이벤트의 생성 시각 (발행 지연 감시용) */
    Optional<LocalDateTime> oldestPendingCreatedAt();

    List<OutboxEvent> findDead();

    /** 보관 기간이 지난 PUBLISHED 이벤트를 최대 batchSize 건 삭제하고 삭제 건수를 돌려준다 */
    int deletePublishedBefore(LocalDateTime before, int batchSize);

    void deleteAll();
}
