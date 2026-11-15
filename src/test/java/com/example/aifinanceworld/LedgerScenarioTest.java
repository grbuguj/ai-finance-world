package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.IntegrityUseCase;
import com.aifinance.application.usecase.InvestUseCase;
import com.aifinance.application.usecase.RefundUseCase;
import com.aifinance.application.usecase.SpendUseCase;
import com.aifinance.application.usecase.TransferUseCase;
import com.aifinance.domain.account.Account;
import com.aifinance.domain.account.AccountType;
import com.aifinance.domain.account.InsufficientBalanceException;
import com.aifinance.domain.agent.Agent;
import com.aifinance.domain.transfer.TransactionType;
import com.aifinance.domain.transfer.Transfer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 소비 / 환불 / 투자 / 급여 / 잔액 부족 시나리오별로
 * 잔액 변화와 복식부기 무결성(IntegrityUseCase)이 함께 맞는지 확인한다.
 */
@SpringBootTest(classes = AiFinanceWorldApplication.class)
class LedgerScenarioTest {

    @Autowired SimulationEngine engine;
    @Autowired AccountPort accountPort;
    @Autowired AgentUseCase agentUseCase;
    @Autowired TransferUseCase transferUseCase;
    @Autowired SpendUseCase spendUseCase;
    @Autowired RefundUseCase refundUseCase;
    @Autowired InvestUseCase investUseCase;
    @Autowired IntegrityUseCase integrityUseCase;

    Account alice;   // 첫 번째 에이전트 계좌
    Account bob;     // 두 번째 에이전트 계좌
    Account merchant;

    @BeforeEach
    void setUp() {
        engine.reset(); // 거래/원장 비우고 초기 자본금 재지급 → 깨끗한 상태
        List<Agent> agents = agentUseCase.findAll();
        alice = agents.get(0).getAccount();
        bob = agents.get(1).getAccount();
        merchant = accountPort.findByType(AccountType.MERCHANT).get(0);
        assertTrue(integrityUseCase.check().balanced(), "초기화 직후 무결성");
    }

    private BigDecimal balanceOf(Account a) {
        return accountPort.findById(a.getId()).orElseThrow().getBalance();
    }

    @Test
    void 소비하면_에이전트는_줄고_가맹점은_늘며_원장은_균형() {
        BigDecimal before = balanceOf(alice);
        BigDecimal merchantBefore = balanceOf(merchant);

        Transfer t = spendUseCase.spend(alice.getId(), merchant.getId(), new BigDecimal("30000"), "테스트 결제");

        assertEquals(TransactionType.SPEND, t.getTxType());
        assertEquals(0, before.subtract(new BigDecimal("30000")).compareTo(balanceOf(alice)));
        assertEquals(0, merchantBefore.add(new BigDecimal("30000")).compareTo(balanceOf(merchant)));
        assertTrue(integrityUseCase.check().balanced());
    }

    @Test
    void 환불하면_원상복구되고_두번째_환불은_거절() {
        BigDecimal before = balanceOf(alice);
        Transfer spend = spendUseCase.spend(alice.getId(), merchant.getId(), new BigDecimal("50000"), "환불 테스트");

        Transfer refund = refundUseCase.refund(spend.getId());
        assertEquals(TransactionType.REFUND, refund.getTxType());
        assertEquals(spend.getId(), refund.getOriginalTransferId());
        assertEquals(0, before.compareTo(balanceOf(alice)), "환불 후 잔액 원상복구");

        assertThrows(IllegalStateException.class, () -> refundUseCase.refund(spend.getId()), "중복 환불 거절");
        assertEquals(0, before.compareTo(balanceOf(alice)), "중복 환불 시도 후에도 잔액 그대로");
        assertTrue(integrityUseCase.check().balanced());
    }

    @Test
    void 송금은_환불_대상이_아니다() {
        Transfer transfer = transferUseCase.transfer(alice.getId(), bob.getId(), new BigDecimal("10000"), "송금");
        assertThrows(IllegalStateException.class, () -> refundUseCase.refund(transfer.getId()));
        assertTrue(integrityUseCase.check().balanced());
    }

    @Test
    void 투자_수익_정산() {
        BigDecimal before = balanceOf(alice);
        var result = investUseCase.invest(alice.getId(), new BigDecimal("100000"), 0, 5);
        assertEquals(0, before.subtract(new BigDecimal("100000")).compareTo(balanceOf(alice)));

        Transfer settle = investUseCase.settle(result.investment().getId(), new BigDecimal("0.10"));
        assertEquals(TransactionType.INVEST_RETURN, settle.getTxType());
        assertEquals(0, new BigDecimal("110000").compareTo(settle.getAmount()), "원금 × 1.10");
        assertEquals(0, before.add(new BigDecimal("10000")).compareTo(balanceOf(alice)));
        assertTrue(integrityUseCase.check().balanced());
    }

    @Test
    void 투자_손실_정산과_중복_정산_거절() {
        BigDecimal before = balanceOf(alice);
        var result = investUseCase.invest(alice.getId(), new BigDecimal("100000"), 0, 5);

        Transfer settle = investUseCase.settle(result.investment().getId(), new BigDecimal("-0.25"));
        assertEquals(0, new BigDecimal("75000").compareTo(settle.getAmount()), "원금 × 0.75 (음수 수익률)");
        assertEquals(0, before.subtract(new BigDecimal("25000")).compareTo(balanceOf(alice)));

        assertThrows(IllegalStateException.class,
                () -> investUseCase.settle(result.investment().getId(), new BigDecimal("0.50")), "중복 정산 거절");
        assertTrue(integrityUseCase.check().balanced());
    }

    @Test
    void 투자_전액_손실이면_정산_거래_없음() {
        BigDecimal before = balanceOf(alice);
        var result = investUseCase.invest(alice.getId(), new BigDecimal("100000"), 0, 5);
        Transfer settle = investUseCase.settle(result.investment().getId(), new BigDecimal("-1.50"));
        assertNull(settle, "-100% 아래는 -100% 로 제한, 지급액 0");
        assertEquals(0, before.subtract(new BigDecimal("100000")).compareTo(balanceOf(alice)));
        assertTrue(integrityUseCase.check().balanced());
    }

    @Test
    void 급여_지급() {
        BigDecimal before = balanceOf(alice);
        Agent first = agentUseCase.findAll().get(0);
        BigDecimal expected = BigDecimal.valueOf(first.getSalary()).multiply(new BigDecimal("0.05"));

        agentUseCase.paySalary();

        assertEquals(0, before.add(expected).compareTo(balanceOf(alice)));
        assertTrue(integrityUseCase.check().balanced());
    }

    @Test
    void 잔액_초과_결제는_예외와_함께_전체_롤백() {
        BigDecimal before = balanceOf(alice);
        BigDecimal merchantBefore = balanceOf(merchant);

        assertThrows(InsufficientBalanceException.class,
                () -> spendUseCase.spend(alice.getId(), merchant.getId(), before.add(BigDecimal.ONE), "초과 결제"));

        assertEquals(0, before.compareTo(balanceOf(alice)));
        assertEquals(0, merchantBefore.compareTo(balanceOf(merchant)));
        assertTrue(integrityUseCase.check().balanced());
    }
}
