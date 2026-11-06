package com.aifinance.adapter.out.cache;

import com.aifinance.application.port.out.BalanceCachePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Redis 잔액 캐시. 값은 "version:balance" 형태로 저장한다.
 * 커밋 후 이벤트가 순서를 바꿔 도착해도, 더 큰 version(거래 id)일 때만 덮어써서 오래된 잔액으로 되돌아가지 않는다.
 */
@Component
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
public class RedisBalanceCacheAdapter implements BalanceCachePort {

    private static final String PREFIX = "balance:";
    private static final Duration TTL = Duration.ofMinutes(10);

    // KEYS[1]=key, ARGV[1]=version, ARGV[2]=value, ARGV[3]=ttl(ms)
    private static final DefaultRedisScript<Long> PUT_IF_NEWER = new DefaultRedisScript<>(
            "local cur = redis.call('get', KEYS[1]) " +
            "if cur then " +
            "  local v = tonumber(string.match(cur, '^(%d+):')) " +
            "  if v and v >= tonumber(ARGV[1]) then return 0 end " +
            "end " +
            "redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3]) " +
            "return 1",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisBalanceCacheAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void put(Long accountId, BigDecimal balance, long version) {
        String value = version + ":" + balance.toPlainString();
        redis.execute(PUT_IF_NEWER, List.of(PREFIX + accountId),
                String.valueOf(version), value, String.valueOf(TTL.toMillis()));
    }

    @Override
    public Optional<BigDecimal> get(Long accountId) {
        String value = redis.opsForValue().get(PREFIX + accountId);
        if (value == null) return Optional.empty();
        int idx = value.indexOf(':');
        return Optional.of(new BigDecimal(idx >= 0 ? value.substring(idx + 1) : value));
    }

    @Override
    public void evictAll() {
        Set<String> keys = redis.keys(PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
