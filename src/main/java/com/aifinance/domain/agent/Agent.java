package com.aifinance.domain.agent;


import com.aifinance.domain.account.Account;
import jakarta.persistence.*;
import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder

@Entity
@Table(name = "agents")
public class Agent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    private String name;
    private Long salary;

    @OneToOne
    @JoinColumn(name = "account_id")
    private Account account;

    private double chanceSpend;
    private double chancePay;
    private double chanceRefund;
    private double chanceInvest;

}
