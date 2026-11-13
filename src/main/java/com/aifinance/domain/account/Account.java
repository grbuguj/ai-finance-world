package com.aifinance.domain.account;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)

@Entity
@Table(name = "accounts")

public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 계좌 소유자 표시명 (에이전트 이름 또는 가맹점/시장 이름)
    private String name;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private AccountType accountType = AccountType.AGENT;

    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    /**
     * 낙관적 락 버전. 실서비스 경로는 비관적 락(SELECT ... FOR UPDATE)을 쓰지만,
     * 버전 컬럼이 있으면 락을 우회한 갱신(버그·배치 등)이 다른 갱신을 덮어쓰는 순간 예외로 드러난다 (Lost Update 이중 방어).
     * 락 전략 비교 실험(LockStrategyBenchmark)의 낙관적 락 전략도 이 컬럼을 쓴다.
     */
    @Version
    private Long version;

    @CreatedDate
    private LocalDateTime createTime;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    public boolean isSystem() {
        return accountType != null && accountType.isSystem();
    }

    /** 출금. 에이전트 계좌는 잔액을 초과해 출금할 수 없다 (도메인 규칙). */
    public void withdraw(BigDecimal amount) {
        if (!isSystem() && this.balance.compareTo(amount) < 0) {
            throw new InsufficientBalanceException(id, balance, amount);
        }
        this.balance = this.balance.subtract(amount);
    }

    public void deposit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }

    /** 시뮬레이션 초기화 전용 - 원장을 비운 뒤 잔액도 0으로 맞춘다. */
    public void resetBalance() {
        this.balance = BigDecimal.ZERO;
    }
}
