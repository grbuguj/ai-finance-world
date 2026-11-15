package com.aifinance.adapter.out.cache;

import com.aifinance.application.port.out.BalanceCachePort;
import com.aifinance.application.port.out.DistributedLockPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

/**
 * Redis 를 쓰지 않을 때(app.redis.enabled=false, 기본값)의 대체 구현.
 * 단일 서버에서는 DB 비관적 락만으로 정합성이 보장되므로 분산 락은 생략하고, 잔액은 DB에서 바로 읽는다.
 */
@Configuration
@ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpCacheConfig {

    @Bean
    public DistributedLockPort noOpDistributedLock() {
        return new DistributedLockPort() {
            @Override
            public Optional<String> tryLock(String key, Duration wait, Duration lease) {
                return Optional.of("local");
            }

            @Override
            public void unlock(String key, String token) {
            }
        };
    }

    @Bean
    public BalanceCachePort noOpBalanceCache() {
        return new BalanceCachePort() {
            @Override
            public void put(Long accountId, BigDecimal balance, long version) {
            }

            @Override
            public Optional<BigDecimal> get(Long accountId) {
                return Optional.empty();
            }

            @Override
            public void evictAll() {
            }
        };
    }
}
