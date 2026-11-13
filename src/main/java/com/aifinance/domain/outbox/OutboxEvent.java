package com.aifinance.domain.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Transactional Outbox — "DB 커밋"과 "메시지 발행"을 원자적으로 묶기 위한 테이블.
 *
 * 문제: 거래를 커밋한 뒤 Kafka 로 보내면, 그 사이에 앱이 죽거나 브로커가 내려가 있으면 이벤트가 사라진다.
 *       (원장에는 거래가 있는데 외부 정산 시스템에는 없는 상태)
 * 해결: 거래와 같은 트랜잭션에서 이 테이블에 이벤트를 INSERT 한다. 커밋되면 둘 다 남고, 롤백되면 둘 다 사라진다.
 *       별도 릴레이(OutboxRelayService)가 PENDING 행을 읽어 브로커로 보내고, ack 를 받으면 PUBLISHED 로 바꾼다.
 *
 * 전달 보장은 at-least-once 다. (발행은 성공했지만 PUBLISHED 표시 전에 죽으면 다시 보낸다)
 * 그래서 소비자는 반드시 멱등해야 한다 → SettlementInbox 가 transferId 로 중복을 걸러낸다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "outbox_events",
        indexes = {
                // 릴레이 폴링: WHERE status = 'PENDING' AND next_attempt_at <= ? ORDER BY id
                @Index(name = "idx_outbox_status_next", columnList = "status, next_attempt_at, id"),
                // 대사: 거래 id 로 Outbox 상태 조회 (전달 중 판별)
                @Index(name = "idx_outbox_aggregate", columnList = "aggregate_id")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_outbox_event_id", columnNames = "event_id"))
public class OutboxEvent {

    private static final Duration BASE_BACKOFF = Duration.ofMillis(500);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 전역 고유 이벤트 id — 추적/로그 상관관계용으로 메시지 헤더에 실린다 */
    @Column(name = "event_id", length = 36, nullable = false)
    private String eventId;

    @Column(length = 30, nullable = false)
    private String aggregateType;

    /** 원본 거래 id (transfers.id). 소비자는 이 값으로 중복을 판별한다 */
    @Column(nullable = false)
    private Long aggregateId;

    @Column(length = 50, nullable = false)
    private String eventType;

    /** 파티션 키 — 같은 키(보내는 계좌)의 이벤트는 같은 파티션으로 가서 순서가 유지된다 */
    @Column(length = 50, nullable = false)
    private String messageKey;

    @Column(length = 4000, nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private OutboxStatus status;

    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(length = 500)
    private String lastError;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime publishedAt;

    public static OutboxEvent pending(String aggregateType, Long aggregateId, String eventType,
                                      String messageKey, String payload, LocalDateTime now) {
        OutboxEvent e = new OutboxEvent();
        e.eventId = UUID.randomUUID().toString();
        e.aggregateType = aggregateType;
        e.aggregateId = aggregateId;
        e.eventType = eventType;
        e.messageKey = messageKey;
        e.payload = payload;
        e.status = OutboxStatus.PENDING;
        e.attempts = 0;
        e.createdAt = now;
        e.nextAttemptAt = now;
        return e;
    }

    public void markPublished(LocalDateTime now) {
        this.attempts++;
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = now;
        this.lastError = null;
    }

    /**
     * 발행 실패 기록. 지수 백오프(0.5s, 1s, 2s ... 최대 30s)로 다음 시도 시각을 미루고,
     * 최대 횟수에 도달하면 DEAD 로 격리해 무한 재시도가 다른 이벤트를 막지 않게 한다.
     */
    public void markFailed(String error, LocalDateTime now, int maxAttempts) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        if (attempts >= maxAttempts) {
            this.status = OutboxStatus.DEAD;
            return;
        }
        this.nextAttemptAt = now.plus(backoff(attempts));
    }

    /** 운영자가 원인을 해결한 뒤 DEAD 이벤트를 다시 발행 대기열로 돌린다 */
    public void requeue(LocalDateTime now) {
        if (status != OutboxStatus.DEAD) {
            throw new IllegalStateException("DEAD 상태의 이벤트만 재처리할 수 있습니다: " + eventId);
        }
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
    }

    static Duration backoff(int attempts) {
        long multiplier = 1L << Math.min(attempts - 1, 20);
        Duration d = BASE_BACKOFF.multipliedBy(multiplier);
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }
}
