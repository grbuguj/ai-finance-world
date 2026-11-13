package com.aifinance.application.service;

import com.aifinance.application.port.out.DistributedLockPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * 거래 실행 템플릿: [분산 락 획득] → [DB 트랜잭션 시작 ~ 커밋] → [분산 락 해제]
 *
 * 분산 락은 반드시 트랜잭션보다 넓은 범위를 감싸야 한다.
 * 트랜잭션 안에서 락을 풀면, 커밋 전에 다른 서버가 들어와 커밋 전 잔액을 읽을 수 있기 때문.
 * 락 키도 계좌 ID 오름차순으로 잡아 분산 락 수준의 데드락을 막는다.
 */
@Component
public class AccountLockExecutor {

    private final DistributedLockPort lockPort;
    private final TransactionTemplate transactionTemplate;
    private final Duration waitTime;
    private final Duration leaseTime;
    private final boolean distributedEnabled;

    public AccountLockExecutor(DistributedLockPort lockPort,
                               PlatformTransactionManager transactionManager,
                               @Value("${app.lock.wait-ms:3000}") long waitMs,
                               @Value("${app.lock.lease-ms:5000}") long leaseMs,
                               @Value("${app.lock.distributed.enabled:false}") boolean distributedEnabled) {
        this.lockPort = lockPort;
        this.distributedEnabled = distributedEnabled;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.waitTime = Duration.ofMillis(waitMs);
        this.leaseTime = Duration.ofMillis(leaseMs);
    }

    public <T> T execute(List<Long> accountIds, Supplier<T> work) {
        Deque<String[]> acquired = new ArrayDeque<>();
        try {
            // 분산 락을 끄면 DB 비관적 락(SELECT ... FOR UPDATE)만으로 정합성을 지킨다 (단일 DB 이므로 충분)
            for (Long id : distributedEnabled ? new TreeSet<>(accountIds) : new TreeSet<Long>()) {
                String key = "lock:account:" + id;
                Optional<String> token = lockPort.tryLock(key, waitTime, leaseTime);
                if (token.isEmpty()) {
                    throw new IllegalStateException("분산 락 획득 실패: " + key);
                }
                acquired.push(new String[]{key, token.get()});
            }
            return transactionTemplate.execute(status -> work.get());
        } finally {
            while (!acquired.isEmpty()) {
                String[] lock = acquired.pop();
                lockPort.unlock(lock[0], lock[1]);
            }
        }
    }
}
