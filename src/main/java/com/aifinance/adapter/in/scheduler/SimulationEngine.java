package com.aifinance.adapter.in.scheduler;

import com.aifinance.adapter.in.web.FeedSseService;
import com.aifinance.adapter.out.llm.LlmClient;
import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.port.out.AgentPort;
import com.aifinance.application.port.out.LedgerPort;
import com.aifinance.application.port.out.TransferPort;
import com.aifinance.application.usecase.TransferUseCase;
import com.aifinance.domain.agent.Agent;
import com.aifinance.domain.transfer.Transfer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class SimulationEngine {

    public enum State { RUNNING, PAUSED, STOPPED }

    private final TransferUseCase transferUseCase;
    private final AgentPort agentPort;
    private final AccountPort accountPort;
    private final TransferPort transferPort;
    private final LedgerPort ledgerPort;
    private final LlmClient llmClient;
    private final FeedSseService feedSseService;

    private final AtomicLong tickIntervalMs = new AtomicLong(1000);
    private volatile long lastTickAt = 0;
    private volatile State state = State.RUNNING;

    public SimulationEngine(TransferUseCase transferUseCase, AgentPort agentPort,
                            AccountPort accountPort, TransferPort transferPort,
                            LedgerPort ledgerPort, LlmClient llmClient,
                            FeedSseService feedSseService) {
        this.transferUseCase = transferUseCase;
        this.agentPort = agentPort;
        this.accountPort = accountPort;
        this.transferPort = transferPort;
        this.ledgerPort = ledgerPort;
        this.llmClient = llmClient;
        this.feedSseService = feedSseService;
    }

    public void setTickIntervalMs(long ms) { tickIntervalMs.set(Math.max(1, Math.min(5000, ms))); }
    public long getTickIntervalMs()        { return tickIntervalMs.get(); }
    public State getState()                { return state; }

    public void start()  { state = State.RUNNING; }
    public void pause()  { state = State.PAUSED; }
    public void stop()   { state = State.STOPPED; }

    @Transactional
    public void reset() {
        state = State.STOPPED;
        // 1. 거래 내역 + 원장 전부 삭제 (FK 순서 주의: ledger → transfer)
        ledgerPort.deleteAll();
        transferPort.deleteAll();
        // 2. 모든 에이전트 계좌를 salary(초기 잔액)로 복원
        agentPort.findAll().forEach(a -> {
            a.getAccount().getBalance(); // lazy load 방지
            BigDecimal initial = BigDecimal.valueOf(a.getSalary());
            // balance 필드를 초기값으로 직접 세팅하기 위해 deposit/withdraw 사용
            BigDecimal current = a.getAccount().getBalance();
            BigDecimal diff = initial.subtract(current);
            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                a.getAccount().deposit(diff);
            } else if (diff.compareTo(BigDecimal.ZERO) < 0) {
                a.getAccount().withdraw(diff.abs());
            }
            accountPort.save(a.getAccount());
        });
    }

    private static String toJson(Transfer t, String senderName, String receiverName) {
        return "{" +
                "\"id\":" + t.getId() + "," +
                "\"senderName\":\"" + esc(senderName) + "\"," +
                "\"receiverName\":\"" + esc(receiverName) + "\"," +
                "\"amount\":" + t.getAmount().toPlainString() + "," +
                "\"type\":\"" + t.getType().name() + "\"," +
                "\"description\":\"" + esc(t.getDescription()) + "\"," +
                "\"createdAt\":\"" + t.getCreatedAt() + "\"" +
                "}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // 5ms 마다 기반 틱 — 실제 간격은 tickIntervalMs 로 조절 (최대 1ms까지 지원)
    @Scheduled(fixedRate = 5)
    public void tick() {
        if (state != State.RUNNING) return;
        long now = System.currentTimeMillis();
        if (now - lastTickAt < tickIntervalMs.get()) return;
        lastTickAt = now;

        List<Agent> agents = agentPort.findAll();
        for (Agent agent : agents) {
            if (Math.random() < agent.getChancePay()) {

                List<Agent> others = agents.stream()
                        .filter(a -> !a.getId().equals(agent.getId()))
                        .toList();
                Agent target = others.get((int) (Math.random() * others.size()));

                BigDecimal balance = agent.getAccount().getBalance();
                double ratio = 0.03 + Math.random() * 0.17;
                BigDecimal amount = balance.multiply(BigDecimal.valueOf(ratio))
                        .setScale(-4, RoundingMode.DOWN);

                if (amount.signum() <= 0) continue;

                String reason = llmClient.generateReason(agent.getName(), target.getName(), amount);
                try {
                    Transfer saved = transferUseCase.transfer(
                            agent.getAccount().getId(),
                            target.getAccount().getId(),
                            amount,
                            reason);
                    feedSseService.publish(toJson(saved, agent.getName(), target.getName()));
                } catch (Exception e) {
                    // 잔액 부족 등 정상 실패 — 무시
                }
            }
        }
    }
}
