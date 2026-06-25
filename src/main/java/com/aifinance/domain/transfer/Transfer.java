package com.aifinance.domain.transfer;

import com.aifinance.domain.account.Account;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
///  @Getter 어노테이션 - transfer.getAmount() 와 같은 방식으로, transfer 테이블의 amount를 읽을 수 있음

@NoArgsConstructor
///  @Getter 어노테이션 - transfer.getAmount() 와 같은 방식으로, transfer 테이블의 amount를 읽을 수 있음


@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)

@Entity
@Table(name = "transfers")

public class Transfer {
    /// 테이블의 PK 설정 - @Id
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "sender_id")
    private Account sender;

    @ManyToOne
    @JoinColumn(name = "receiver_id")
    private Account receiver;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private TransferStatus type;

    // LLM이 생성한 자연어 사유 (예: "어제 먹은 삼겹살 값"). 처음엔 null, 스케줄러가 채움
    @Column(length = 255)
    private String description;

    @CreatedDate
    private LocalDateTime createdAt;

    // 도메인 메서드 - Setter 대신 의미있는 메서드로 사유를 붙인다
    public void describe(String description) {
        this.description = description;
    }
}
