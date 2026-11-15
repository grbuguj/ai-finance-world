package com.aifinance.application.usecase;

import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.port.out.InvestmentPort;
import com.aifinance.application.service.AccountLockExecutor;
import com.aifinance.application.service.LedgerPostingService;
import com.aifinance.domain.account.Account;
import com.aifinance.domain.account.AccountType;
import com.aifinance.domain.investment.Investment;
import com.aifinance.domain.transfer.TransactionType;
import com.aifinance.domain.transfer.Transfer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 투자: 에이전트 → 투자시장 납입 후, 만기 틱에 수익률 r ~ N(μ, σ²) 로 정산.
 * r 은 음수가 될 수 있고(손실), 최저 -100%(원금 전액 손실)로 제한된다.
 */
@Service
public class InvestUseCase {

    private final AccountPort accountPort;
    private final InvestmentPort investmentPort;
    private final AccountLockExecutor lockExecutor;
    private final LedgerPostingService postingService;
    private final double meanReturn;
    private final double stdDevReturn;

    public InvestUseCase(AccountPort accountPort, InvestmentPort investmentPort,
                         AccountLockExecutor lockExecutor, LedgerPostingService postingService,
                         @Value("${app.invest.mean-return:0.02}") double meanReturn,
                         @Value("${app.invest.stddev-return:0.10}") double stdDevReturn) {
        this.accountPort = accountPort;
        this.investmentPort = investmentPort;
        this.lockExecutor = lockExecutor;
        this.postingService = postingService;
        this.meanReturn = meanReturn;
        this.stdDevReturn = stdDevReturn;
    }

    public record InvestResult(Investment investment, Transfer transfer) {}

    /** 투자 납입 */
    public InvestResult invest(Long agentAccountId, BigDecimal amount, long currentTick, long holdTicks) {
        Long marketId = market().getId();
        return lockExecutor.execute(List.of(agentAccountId, marketId), () -> {
            Transfer transfer = postingService.post(agentAccountId, marketId, amount,
                    TransactionType.INVEST, "투자 납입 (만기 " + holdTicks + "틱)", null);
            Investment investment = investmentPort.save(Investment.builder()
                    .accountId(agentAccountId)
                    .principal(amount)
                    .openedTick(currentTick)
                    .maturesAtTick(currentTick + holdTicks)
                    .investTransferId(transfer.getId())
                    .build());
            return new InvestResult(investment, transfer);
        });
    }

    /** 지정한 수익률로 정산. 전액 손실이면 정산 거래 없이 종료하고 null 을 돌려준다. */
    public Transfer settle(Long investmentId, BigDecimal rate) {
        Investment peek = investmentPort.findById(investmentId)
                .orElseThrow(() -> new IllegalArgumentException("투자 없음: " + investmentId));
        Long agentAccountId = peek.getAccountId();
        Long marketId = market().getId();

        return lockExecutor.execute(List.of(agentAccountId, marketId), () -> {
            Investment investment = investmentPort.findByIdWithLock(investmentId)
                    .orElseThrow(() -> new IllegalArgumentException("투자 없음: " + investmentId));
            BigDecimal payout = investment.payoutFor(rate);

            Transfer transfer = null;
            if (payout.signum() > 0) {
                String pct = rate.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString();
                transfer = postingService.post(marketId, agentAccountId, payout, TransactionType.INVEST_RETURN,
                        "투자 정산 (수익률 " + (rate.signum() >= 0 ? "+" : "") + pct + "%)", investment.getInvestTransferId());
            }
            investment.settle(rate, payout, transfer == null ? null : transfer.getId());
            investmentPort.save(investment);
            return transfer;
        });
    }

    /** 만기 도래한 투자를 모두 정산. 수익률은 정규분포에서 샘플링. */
    public List<Transfer> settleMatured(long currentTick) {
        List<Transfer> settled = new ArrayList<>();
        for (Investment investment : investmentPort.findMatured(currentTick)) {
            try {
                Transfer t = settle(investment.getId(), sampleReturnRate());
                if (t != null) settled.add(t);
            } catch (RuntimeException e) {
                // 동시에 다른 곳에서 정산된 경우 등 - 건너뜀
            }
        }
        return settled;
    }

    /** r ~ N(μ, σ²) */
    public BigDecimal sampleReturnRate() {
        double r = meanReturn + stdDevReturn * ThreadLocalRandom.current().nextGaussian();
        return BigDecimal.valueOf(Math.max(-1.0, r)).setScale(6, RoundingMode.HALF_UP);
    }

    public long openCount() {
        return investmentPort.countOpen();
    }

    private Account market() {
        List<Account> markets = accountPort.findByType(AccountType.MARKET);
        if (markets.isEmpty()) {
            throw new IllegalStateException("투자시장 계좌가 없습니다");
        }
        return markets.get(0);
    }
}
