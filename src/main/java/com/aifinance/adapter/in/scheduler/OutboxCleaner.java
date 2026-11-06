package com.aifinance.adapter.in.scheduler;

import com.aifinance.application.port.out.OutboxPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 발행 완료(PUBLISHED) Outbox 이벤트 정리. 정리하지 않으면 거래 수만큼 테이블이 끝없이 커지고
 * 릴레이 폴링 인덱스도 함께 비대해진다.
 *
 * - PENDING / DEAD 는 지우지 않는다 (아직 전달되지 않았거나 운영자 확인이 필요한 이벤트)
 * - 보관 기간(기본 7일)은 일마감 대사 기간(1일)보다 길어야 한다. 대사가 "전달 중"과 "유실"을 구분할 때 Outbox 상태를 보기 때문
 * - 한 번에 지우지 않고 1,000건씩 짧은 트랜잭션으로 나눠 지운다 (대량 DELETE 의 긴 락·undo 비대 방지)
 */
@Component
public class OutboxCleaner {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleaner.class);

    private final OutboxPort outboxPort;
    private final long retentionDays;
    private final int batchSize;

    public OutboxCleaner(OutboxPort outboxPort,
                         @Value("${app.outbox.retention-days:7}") long retentionDays,
                         @Value("${app.outbox.cleanup-batch-size:1000}") int batchSize) {
        this.outboxPort = outboxPort;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.outbox.cleanup-interval-ms:3600000}")
    public void cleanup() {
        cleanupBefore(LocalDateTime.now().minusDays(retentionDays));
    }

    /** @return 삭제한 총 건수 */
    public int cleanupBefore(LocalDateTime before) {
        int total = 0;
        int deleted;
        do {
            deleted = outboxPort.deletePublishedBefore(before, batchSize);
            total += deleted;
        } while (deleted == batchSize);
        if (total > 0) {
            log.info("[Outbox] 보관 기간이 지난 발행 완료 이벤트 {}건 정리 (기준 {})", total, before);
        }
        return total;
    }
}
