package com.aifinance.application.port.out;

import java.time.Duration;
import java.util.Optional;

/**
 * 분산 락 포트. 여러 서버 인스턴스가 같은 계좌를 동시에 건드리지 못하게 막는다.
 * 구현: Redis(SET NX PX) / 비활성 시 NoOp.
 */
public interface DistributedLockPort {
    /** 락 획득 시 해제용 토큰을 돌려준다. wait 시간 안에 못 얻으면 empty. */
    Optional<String> tryLock(String key, Duration wait, Duration lease);
    void unlock(String key, String token);
}
