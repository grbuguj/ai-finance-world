package com.aifinance.application.service;

import com.aifinance.application.port.out.IdempotencyPort;
import com.aifinance.application.port.out.TransferPort;
import com.aifinance.domain.idempotency.DuplicateIdempotencyKeyException;
import com.aifinance.domain.idempotency.IdempotencyKeyMismatchException;
import com.aifinance.domain.idempotency.IdempotencyRecord;
import com.aifinance.domain.transfer.Transfer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 멱등성 보장 실행기.
 *
 *  1) 이미 처리된 키면 → 저장된 거래를 그대로 돌려준다 (재처리 없음)
 *  2) 처음 보는 키면 → [분산 락 → 트랜잭션 { 키 INSERT → 기장 → 키에 거래 id 기록 } → 커밋]
 *  3) 동시에 같은 키가 들어오면 → DB 유니크 제약으로 하나만 INSERT 성공,
 *     나머지는 롤백 후 먼저 처리된 결과가 커밋되길 기다렸다가 같은 결과를 돌려준다
 *
 *  - 키와 거래가 같은 트랜잭션이므로 "돈은 나갔는데 키는 없다" / "키는 있는데 돈은 안 나갔다" 가 불가능
 *  - 실패한 요청(잔액 부족 등)은 키도 롤백되므로 같은 키로 재시도할 수 있다
 */
@Component
public class IdempotentExecutor {

    public record Result(Transfer transfer, boolean replayed) {}

    private final IdempotencyPort idempotencyPort;
    private final TransferPort transferPort;
    private final AccountLockExecutor lockExecutor;
    private final long waitMs;

    public IdempotentExecutor(IdempotencyPort idempotencyPort, TransferPort transferPort,
                              AccountLockExecutor lockExecutor,
                              @Value("${app.idempotency.wait-ms:5000}") long waitMs) {
        this.idempotencyPort = idempotencyPort;
        this.transferPort = transferPort;
        this.lockExecutor = lockExecutor;
        this.waitMs = waitMs;
    }

    public Result execute(String key, String requestHash, List<Long> accountIds, Supplier<Transfer> work) {
        if (key == null || key.isBlank()) {
            return new Result(lockExecutor.execute(accountIds, work), false);
        }
        if (key.length() > 100) {
            throw new IllegalArgumentException("Idempotency-Key 는 100자 이하여야 합니다");
        }

        // 1) 빠른 경로: 이미 처리된 키
        Optional<Result> done = replay(key, requestHash);
        if (done.isPresent()) {
            return done.get();
        }

        // 2) 처음 처리
        try {
            Transfer transfer = lockExecutor.execute(accountIds, () -> {
                IdempotencyRecord record = idempotencyPort.insert(key, requestHash);
                Transfer t = work.get();
                record.complete(t.getId());
                return t;
            });
            return new Result(transfer, false);
        } catch (DuplicateIdempotencyKeyException e) {
            // 3) 동시에 같은 키 — 먼저 들어간 요청의 커밋을 기다렸다가 그 결과를 돌려준다
            return awaitResult(key, requestHash);
        }
    }

    private Optional<Result> replay(String key, String requestHash) {
        return idempotencyPort.find(key).map(record -> {
            if (!record.matches(requestHash)) {
                throw new IdempotencyKeyMismatchException(key);
            }
            Transfer original = transferPort.findById(record.getTransferId())
                    .orElseThrow(() -> new IllegalStateException("멱등성 키의 원거래가 없습니다: " + key));
            return new Result(original, true);
        });
    }

    private Result awaitResult(String key, String requestHash) {
        long deadline = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < deadline) {
            Optional<Result> done = replay(key, requestHash);
            if (done.isPresent()) {
                return done.get();
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 먼저 들어온 요청이 실패(롤백)했거나 아직 처리 중 — 클라이언트가 같은 키로 다시 시도하면 된다
        throw new IllegalStateException("같은 Idempotency-Key 요청이 처리 중입니다. 잠시 후 다시 시도하세요: " + key);
    }

    /** 요청 내용 해시 (금액은 1000 과 1000.00 이 같도록 정규화) */
    public static String hash(String operation, Long from, Long to, BigDecimal amount) {
        String raw = operation + "|" + from + "|" + to + "|" + amount.stripTrailingZeros().toPlainString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
