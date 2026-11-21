package com.aifinance.domain.ledger;

import com.aifinance.domain.account.Account;
import com.aifinance.domain.transfer.Transfer;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)

@Immutable // 원장 불변성: 분개는 INSERT 만 된다 — UPDATE 는 막고, 정정은 반대 분개로만 한다
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "account_id")
    private Account account;

    // 이 분개가 속한 거래 (거래 1건 = DEBIT 1건 + CREDIT 1건)
    @ManyToOne
    @JoinColumn(name = "transfer_id")
    private Transfer transfer;

    @Enumerated(EnumType.STRING)
    private EntryType type;

    private BigDecimal amount;

    @CreatedDate
    private LocalDateTime createdAt;

}
