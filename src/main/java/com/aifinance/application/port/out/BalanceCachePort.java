package com.aifinance.application.port.out;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 잔액 캐시 포트. 커밋된 잔액만 캐시하며, 더 최신 거래(version)의 값만 덮어쓴다.
 */
public interface BalanceCachePort {
    void put(Long accountId, BigDecimal balance, long version);
    Optional<BigDecimal> get(Long accountId);
    void evictAll();
}
