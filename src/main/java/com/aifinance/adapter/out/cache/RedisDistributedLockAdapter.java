package com.aifinance.adapter.out.cache;

import com.aifinance.application.port.out.DistributedLockPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Redis 분산 락 (SET key token NX PX lease).
 *
 * - NX: 키가 없을 때만 설정 → 한 번에 한 클라이언트만 획득
 * - PX: 리스 시간 → 락을 쥔 서버가 죽어도 자동 만료 (영구 락 방지)
 * - 해제는 Lua 스크립트로 "내 토큰일 때만 DEL" 을 원자적으로 수행 → 남의 락을 지우는 사고 방지
 */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisDistributedLockAdapter implements DistributedLockPort {

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final long maxBackoffMs;

    /**
     * @param maxBackoffMs 재시도 간격 상한. 스핀 락은 대기 순서가 없어서, 간격이 길면 락이 풀린 순간을 놓친 요청이
     *                     계속 밀려나 대기 한도를 넘긴다(핫 계좌 굶주림). 실측 근거는 ADR-0005.
     */
    public RedisDistributedLockAdapter(StringRedisTemplate redis,
                                       @Value("${app.lock.max-backoff-ms:50}") long maxBackoffMs) {
        this.redis = redis;
        this.maxBackoffMs = Math.max(1, maxBackoffMs);
    }

    @Override
    public Optional<String> tryLock(String key, Duration wait, Duration lease) {
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + wait.toNanos();
        long backoffMs = 1;
        while (true) {
            Boolean ok = redis.opsForValue().setIfAbsent(key, token, lease);
            if (Boolean.TRUE.equals(ok)) {
                return Optional.of(token);
            }
            if (System.nanoTime() >= deadline) {
                return Optional.empty();
            }
            try {
                // 지터: 대기자들이 같은 순간에 몰려 재시도하지 않도록 [1, backoff] 사이 무작위
                Thread.sleep(ThreadLocalRandom.current().nextLong(1, backoffMs + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            backoffMs = Math.min(backoffMs * 2, maxBackoffMs);
        }
    }

    @Override
    public void unlock(String key, String token) {
        redis.execute(UNLOCK_SCRIPT, List.of(key), token);
    }
}
