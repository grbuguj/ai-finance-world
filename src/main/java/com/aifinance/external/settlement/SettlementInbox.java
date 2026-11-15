package com.aifinance.external.settlement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 외부 정산 시스템의 멱등 수신함 (Idempotent Consumer).
 *
 * 브로커는 at-least-once 로 전달하므로 같은 거래 이벤트가 두 번 이상 올 수 있다.
 *  - 처음 보는 거래 id  → 정산 장부에 INSERT (ACCEPTED)
 *  - 이미 있는 거래 id  → 도착 횟수만 올리고 정산은 다시 하지 않음 (DUPLICATE)
 *  - 동시에 같은 id 두 건 → PK 제약으로 하나만 INSERT 성공, 나머지는 DUPLICATE 로 처리
 */
@Service
public class SettlementInbox {

    private static final Logger log = LoggerFactory.getLogger(SettlementInbox.class);

    public enum Outcome { ACCEPTED, DUPLICATE }

    private final SettlementRecordRepository repository;
    private final TransactionTemplate tx;
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();

    public SettlementInbox(SettlementRecordRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public Outcome receive(String payload) {
        SettlementMessage msg = json.readValue(payload, SettlementMessage.class);
        if (msg.transferId() == null || msg.amount() == null) {
            throw new IllegalArgumentException("정산 메시지 필수값 누락: " + payload);
        }
        try {
            Outcome outcome = tx.execute(status -> {
                SettlementRecord existing = repository.findById(msg.transferId()).orElse(null);
                if (existing != null) {
                    existing.markRedelivered();
                    return Outcome.DUPLICATE;
                }
                repository.saveAndFlush(new SettlementRecord(
                        msg.transferId(), msg.txType(), msg.senderAccountId(), msg.receiverAccountId(),
                        msg.amount(), parse(msg.occurredAt()), LocalDateTime.now()));
                return Outcome.ACCEPTED;
            });
            count(outcome, msg);
            return outcome;
        } catch (DataIntegrityViolationException e) {
            // 같은 거래 id 가 동시에 들어와 다른 쪽이 먼저 INSERT 함
            count(Outcome.DUPLICATE, msg);
            return Outcome.DUPLICATE;
        }
    }

    private void count(Outcome outcome, SettlementMessage msg) {
        if (outcome == Outcome.ACCEPTED) {
            accepted.incrementAndGet();
        } else {
            long n = duplicates.incrementAndGet();
            log.info("[외부 정산] 중복 수신 무시 (transfer {}, 누적 중복 {}건)", msg.transferId(), n);
        }
    }

    private static LocalDateTime parse(String s) {
        if (s == null || s.isBlank() || "null".equals(s)) {
            return null;
        }
        return LocalDateTime.parse(s);
    }

    public long acceptedCount() {
        return accepted.get();
    }

    public long duplicateCount() {
        return duplicates.get();
    }

    public void resetCounters() {
        accepted.set(0);
        duplicates.set(0);
    }
}
