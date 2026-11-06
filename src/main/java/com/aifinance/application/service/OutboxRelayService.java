package com.aifinance.application.service;

import com.aifinance.application.event.OutboundMessage;
import com.aifinance.application.port.out.DistributedLockPort;
import com.aifinance.application.port.out.OutboxPort;
import com.aifinance.application.port.out.RelayMetricsPort;
import com.aifinance.application.port.out.TransactionEventPort;
import com.aifinance.domain.outbox.OutboxEvent;
import com.aifinance.domain.outbox.OutboxStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Outbox 릴레이 — PENDING 이벤트를 읽어 브로커로 발행하고 결과를 기록한다.
 *
 * <pre>
 *  [릴레이 락 획득]  ← 서버가 여러 대여도 한 번에 한 릴레이만 (Redis 분산 락 / 단일 서버는 JVM 락)
 *     ① 발행 대상 조회 (PENDING, next_attempt_at ≤ now, id 순, 최대 batchSize 건)
 *     ② 한 건씩 동기 발행 → ack 받으면 PUBLISHED / 실패하면 백오프 후 재시도 예약
 *        실패가 나면 이번 배치는 거기서 멈춘다 → 뒤 이벤트가 앞 이벤트를 추월하지 않음 (순서 보장)
 *     ③ 상태 변경을 한 트랜잭션으로 저장
 *  [릴레이 락 해제]
 * </pre>
 *
 * 왜 at-least-once 인가: ②에서 브로커는 받았는데 ③ 전에 서버가 죽으면 다음 릴레이가 같은 이벤트를 또 보낸다.
 * 이를 막으려면 DB 와 브로커를 묶는 분산 트랜잭션(2PC)이 필요한데, 비용 대비 이득이 없다.
 * 대신 소비자가 거래 id 로 중복을 제거해 "효과상 정확히 한 번(effectively-once)"을 만든다.
 */
@Service
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);
    private static final String RELAY_LOCK_KEY = "lock:outbox-relay";

    public record RelayResult(int published, int failed, int dead) {
        public static final RelayResult SKIPPED = new RelayResult(0, 0, 0);
    }

    public record OutboxStats(long pending, long published, long dead, long oldestPendingAgeMillis) {}

    private final OutboxPort outboxPort;
    private final TransactionEventPort eventPort;
    private final DistributedLockPort lockPort;
    private final RelayMetricsPort metrics;
    private final TransactionTemplate tx;
    private final ReentrantLock localLock = new ReentrantLock();
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelayService(OutboxPort outboxPort, TransactionEventPort eventPort,
                              DistributedLockPort lockPort, RelayMetricsPort metrics,
                              PlatformTransactionManager transactionManager,
                              @Value("${app.outbox.relay.batch-size:200}") int batchSize,
                              @Value("${app.outbox.relay.max-attempts:10}") int maxAttempts) {
        this.outboxPort = outboxPort;
        this.eventPort = eventPort;
        this.lockPort = lockPort;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /** 한 배치를 발행한다. 다른 릴레이가 돌고 있으면 아무것도 하지 않는다. */
    public RelayResult relayOnce() {
        if (!localLock.tryLock()) {
            return RelayResult.SKIPPED;
        }
        try {
            Optional<String> token = lockPort.tryLock(RELAY_LOCK_KEY, Duration.ZERO, Duration.ofSeconds(30));
            if (token.isEmpty()) {
                return RelayResult.SKIPPED;
            }
            try {
                return doRelay();
            } finally {
                lockPort.unlock(RELAY_LOCK_KEY, token.get());
            }
        } finally {
            localLock.unlock();
        }
    }

    /** 대기 중인 이벤트가 없어질 때까지 반복 (테스트/복구 시 사용). 진전이 없으면 멈춘다. */
    public int drain(int maxRounds) {
        int total = 0;
        for (int i = 0; i < maxRounds; i++) {
            RelayResult r = relayOnce();
            total += r.published();
            if (r.published() == 0) {
                break;
            }
        }
        return total;
    }

    private RelayResult doRelay() {
        LocalDateTime now = LocalDateTime.now();
        List<OutboxEvent> batch = tx.execute(status -> outboxPort.findPublishable(now, batchSize));
        if (batch == null || batch.isEmpty()) {
            return new RelayResult(0, 0, 0);
        }

        List<OutboxEvent> changed = new ArrayList<>(batch.size());
        int published = 0, failed = 0, dead = 0;
        for (OutboxEvent event : batch) {
            try {
                eventPort.publish(new OutboundMessage(event.getEventId(), event.getMessageKey(), event.getPayload()));
                LocalDateTime ackAt = LocalDateTime.now();
                event.markPublished(ackAt);
                metrics.recordPublished(Duration.between(event.getCreatedAt(), ackAt).toMillis());
                changed.add(event);
                published++;
            } catch (RuntimeException e) {
                event.markFailed(e.getClass().getSimpleName() + ": " + e.getMessage(), LocalDateTime.now(), maxAttempts);
                changed.add(event);
                if (event.getStatus() == OutboxStatus.DEAD) {
                    dead++;
                    metrics.recordDead();
                    log.error("Outbox 이벤트 DEAD 격리 (event {}, transfer {}, 시도 {}회): {}",
                            event.getEventId(), event.getAggregateId(), event.getAttempts(), e.getMessage());
                    continue; // 격리된 이벤트는 순서 대기열에서 빠지므로 다음 이벤트는 계속 진행
                }
                failed++;
                metrics.recordPublishFailed(e.getClass().getSimpleName());
                log.warn("Outbox 발행 실패, 재시도 예약 (event {}, 시도 {}회): {}",
                        event.getEventId(), event.getAttempts(), e.getMessage());
                break; // 순서 보장: 실패한 이벤트를 건너뛰고 뒤 이벤트를 먼저 보내지 않는다
            }
        }
        tx.executeWithoutResult(status -> outboxPort.saveAll(changed));
        return new RelayResult(published, failed, dead);
    }

    public OutboxStats stats() {
        return tx.execute(status -> {
            long age = outboxPort.oldestPendingCreatedAt()
                    .map(t -> Math.max(0, Duration.between(t, LocalDateTime.now()).toMillis()))
                    .orElse(0L);
            return new OutboxStats(
                    outboxPort.countByStatus(OutboxStatus.PENDING),
                    outboxPort.countByStatus(OutboxStatus.PUBLISHED),
                    outboxPort.countByStatus(OutboxStatus.DEAD),
                    age);
        });
    }

    /** DEAD 이벤트 전체를 재발행 대기열로 되돌린다 (운영자 수동 조치). */
    public int requeueDead() {
        Integer n = tx.execute(status -> {
            List<OutboxEvent> dead = outboxPort.findDead();
            LocalDateTime now = LocalDateTime.now();
            dead.forEach(e -> e.requeue(now));
            outboxPort.saveAll(dead);
            return dead.size();
        });
        return n == null ? 0 : n;
    }
}
