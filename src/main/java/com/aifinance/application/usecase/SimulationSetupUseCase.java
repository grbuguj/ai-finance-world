package com.aifinance.application.usecase;

import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.port.out.AgentPort;
import com.aifinance.application.port.out.BalanceCachePort;
import com.aifinance.application.port.out.IdempotencyPort;
import com.aifinance.application.port.out.InvestmentPort;
import com.aifinance.application.port.out.LedgerPort;
import com.aifinance.application.port.out.OutboxPort;
import com.aifinance.application.port.out.ReconciliationPort;
import com.aifinance.application.port.out.SettlementLedgerPort;
import com.aifinance.application.port.out.TransferPort;
import com.aifinance.application.service.LedgerPostingService;
import com.aifinance.domain.account.Account;
import com.aifinance.domain.account.AccountType;
import com.aifinance.domain.agent.Agent;
import com.aifinance.domain.transfer.TransactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 시뮬레이션 세계 생성 / 초기화.
 * 초기 잔액도 "발행 계정 → 에이전트" 개설(OPENING) 거래로 원장에 기록한다.
 * 그래서 처음부터 모든 계좌의 잔액 = ∑CREDIT - ∑DEBIT 이 성립한다.
 */
@Service
public class SimulationSetupUseCase {

    public static final String CAPITAL_NAME = "발행계정";
    public static final String MARKET_NAME = "한국증권";
    public static final List<String> MERCHANT_NAMES = List.of("쿠팡", "무신사", "배달의민족", "넷플릭스", "KT 통신");

    // name, 초기잔액, chanceSpend, chancePay, chanceInvest, chanceRefund
    private record Persona(String name, long initial, double spend, double pay, double invest, double refund) {}

    private static final List<Persona> PERSONAS = List.of(
            new Persona("일론머스크", 2_900_000, 0.80, 0.40, 0.10, 0.20),
            new Persona("이재용",    1_200_000, 0.30, 0.20, 0.20, 0.10),
            new Persona("워런버핏",  4_200_000, 0.40, 0.20, 0.90, 0.10),
            new Persona("김범수",    3_500_000, 0.40, 0.80, 0.10, 0.10),
            new Persona("손흥민",    1_600_000, 0.75, 0.30, 0.05, 0.30),
            new Persona("유재석",    4_000_000, 0.60, 0.30, 0.20, 0.10),
            new Persona("아이유",    2_700_000, 0.70, 0.20, 0.10, 0.20),
            new Persona("박찬호",    3_100_000, 0.30, 0.20, 0.40, 0.10),
            new Persona("김태희",    2_600_000, 0.95, 0.20, 0.00, 0.50),
            new Persona("이순신",    3_300_000, 0.40, 0.30, 0.20, 0.05)
    );

    private final AccountPort accountPort;
    private final AgentPort agentPort;
    private final LedgerPort ledgerPort;
    private final TransferPort transferPort;
    private final InvestmentPort investmentPort;
    private final BalanceCachePort balanceCachePort;
    private final IdempotencyPort idempotencyPort;
    private final LedgerPostingService postingService;
    private final OutboxPort outboxPort;
    private final SettlementLedgerPort settlementLedgerPort;
    private final ReconciliationPort reconciliationPort;

    public SimulationSetupUseCase(AccountPort accountPort, AgentPort agentPort, LedgerPort ledgerPort,
                                  TransferPort transferPort, InvestmentPort investmentPort,
                                  BalanceCachePort balanceCachePort, IdempotencyPort idempotencyPort,
                                  LedgerPostingService postingService, OutboxPort outboxPort,
                                  SettlementLedgerPort settlementLedgerPort, ReconciliationPort reconciliationPort) {
        this.accountPort = accountPort;
        this.agentPort = agentPort;
        this.ledgerPort = ledgerPort;
        this.transferPort = transferPort;
        this.investmentPort = investmentPort;
        this.balanceCachePort = balanceCachePort;
        this.idempotencyPort = idempotencyPort;
        this.postingService = postingService;
        this.outboxPort = outboxPort;
        this.settlementLedgerPort = settlementLedgerPort;
        this.reconciliationPort = reconciliationPort;
    }

    /** 최초 1회: 시스템 계좌 + 에이전트 10명 생성 후 초기 자본금 지급 */
    @Transactional
    public void initialize() {
        if (agentPort.count() > 0) {
            return;
        }
        accountPort.save(Account.builder().name(CAPITAL_NAME).accountType(AccountType.CAPITAL).build());
        accountPort.save(Account.builder().name(MARKET_NAME).accountType(AccountType.MARKET).build());
        for (String merchant : MERCHANT_NAMES) {
            accountPort.save(Account.builder().name(merchant).accountType(AccountType.MERCHANT).build());
        }
        for (Persona p : PERSONAS) {
            Account account = accountPort.save(Account.builder().name(p.name()).accountType(AccountType.AGENT).build());
            agentPort.save(Agent.builder()
                    .name(p.name())
                    .salary(p.initial())
                    .account(account)
                    .chanceSpend(p.spend())
                    .chancePay(p.pay())
                    .chanceInvest(p.invest())
                    .chanceRefund(p.refund())
                    .build());
        }
        fundOpeningBalances();
    }

    /** 초기화: 거래/원장/투자/이벤트/정산/대사 삭제 → 모든 잔액 0 → 개설 거래로 초기 잔액 재지급 */
    @Transactional
    public void reset() {
        reconciliationPort.deleteAll();
        outboxPort.deleteAll();
        settlementLedgerPort.deleteAll(); // 외부 정산 장부도 같은 세계의 일부이므로 함께 초기화
        investmentPort.deleteAll();
        idempotencyPort.deleteAll(); // 원거래가 삭제되므로 키도 함께 정리
        ledgerPort.deleteAll();   // FK 순서: ledger → transfer
        transferPort.deleteAll();
        for (Account account : accountPort.findAll()) {
            account.resetBalance();
            accountPort.save(account);
        }
        balanceCachePort.evictAll();
        fundOpeningBalances();
    }

    /**
     * 부하 테스트용 계좌 대량 생성. 에이전트와 무관한 계좌이며, 초기 잔액도 개설(OPENING) 거래로 지급해
     * 부하 테스트 후에도 무결성 검증(잔액 = 원장)이 그대로 성립한다.
     */
    @Transactional
    public List<Long> createFundedAccounts(String prefix, int count, BigDecimal balance) {
        Long capitalId = accountPort.findByType(AccountType.CAPITAL).get(0).getId();
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Account account = accountPort.save(Account.builder()
                    .name(String.format("%s-%04d", prefix, i))
                    .accountType(AccountType.AGENT)
                    .build());
            postingService.post(capitalId, account.getId(), balance, TransactionType.OPENING, "부하 테스트 초기 자본금", null);
            ids.add(account.getId());
        }
        return ids;
    }

    private void fundOpeningBalances() {
        Long capitalId = accountPort.findByType(AccountType.CAPITAL).get(0).getId();
        for (Agent agent : agentPort.findAll()) {
            postingService.post(capitalId, agent.getAccount().getId(),
                    BigDecimal.valueOf(agent.getSalary()), TransactionType.OPENING, "초기 자본금", null);
        }
    }
}
