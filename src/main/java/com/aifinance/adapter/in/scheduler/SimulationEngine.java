package com.aifinance.adapter.in.scheduler;

import com.aifinance.adapter.in.web.FeedSseService;
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
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class SimulationEngine {

    public enum State { RUNNING, PAUSED, STOPPED }

    private static final List<String> REASONS = List.of(
            "어제 먹은 삼겹살 값", "지난주 치킨 값", "새벽 라면 끓여줘서", "떡볶이 먹은 거 계산",
            "곱창 내가 쏜다더니 결국", "편의점 야식 대납", "오마카세 더치페이", "술값 n빵",
            "점심 밥값", "커피 값 더치페이", "배달 음식 더치페이", "해장국 먹은 거 정산",
            "마라탕 중독 비용", "새벽 2시 피자 값", "이번 달 생활비", "월세 이체",
            "카드값 이체", "공과금 분할", "인터넷 요금 반반", "넷플릭스 구독비",
            "헬스장 회원권 분할", "주차비 반반", "빌린 돈 갚기", "생일 축하금",
            "결혼 축의금 대납", "경조사비", "이사 도움 사례금", "명절 용돈",
            "스터디 회비", "동아리 회비", "콘서트 티켓 분할", "여행 경비 정산",
            "카풀비 정산", "택시비 분할", "게임 아이템 거래", "온라인 쇼핑 대납",
            "선물 공동구매", "운동 내기 벌금", "지각비", "카톡 씹은 벌금",
            "다이어트 실패 벌금", "늦잠 벌금", "내기 골프 진 거", "스쿼트 내기 졌음",
            "그냥 줌", "왜 줬는지 모르겠음", "술김에 쏜다고 했음", "기분이 너무 좋아서",
            "갑자기 미안해서", "생각보다 고마워서", "언젠가 받을 거 미리", "묻지마 송금",
            "복권 당첨 기념", "연봉 협상 성공 기념", "승진 자축", "퇴근 기념",
            "월급날 기념", "시험 끝난 기념", "헤어진 기념", "재회 기념",
            "그냥 보고싶어서", "너 생각나서", "빚진 기분이 들어서", "내 양심이 허락하지 않아"
    );
    private static final Random RANDOM = new Random();

    private final TransferUseCase transferUseCase;
    private final AgentPort agentPort;
    private final AccountPort accountPort;
    private final TransferPort transferPort;
    private final LedgerPort ledgerPort;
    private final FeedSseService feedSseService;

    private final AtomicLong tickIntervalMs = new AtomicLong(1000);
    private volatile long lastTickAt = 0;
    private volatile State state = State.RUNNING;

    public SimulationEngine(TransferUseCase transferUseCase, AgentPort agentPort,
                            AccountPort accountPort, TransferPort transferPort,
                            LedgerPort ledgerPort, FeedSseService feedSseService) {
        this.transferUseCase = transferUseCase;
        this.agentPort = agentPort;
        this.accountPort = accountPort;
        this.transferPort = transferPort;
        this.ledgerPort = ledgerPort;
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

                String reason = REASONS.get(RANDOM.nextInt(REASONS.size()));
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
