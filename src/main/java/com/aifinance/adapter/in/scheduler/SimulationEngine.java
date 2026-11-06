package com.aifinance.adapter.in.scheduler;

import com.aifinance.adapter.in.web.FeedSseService;
import com.aifinance.adapter.in.web.dto.FeedItem;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.InvestUseCase;
import com.aifinance.application.usecase.RefundUseCase;
import com.aifinance.application.usecase.SimulationSetupUseCase;
import com.aifinance.application.usecase.SpendUseCase;
import com.aifinance.application.usecase.TransferUseCase;
import com.aifinance.domain.account.Account;
import com.aifinance.domain.agent.Agent;
import com.aifinance.domain.transfer.Transfer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 멀티에이전트 시뮬레이션 틱 엔진.
 *
 * 매 틱마다 에이전트별로 4가지 행동의 발생 여부를 독립적인 베르누이 시행으로 결정한다.
 *   소비(chanceSpend) / 송금(chancePay) / 투자(chanceInvest) / 환불(chanceRefund)
 * 그리고 만기 도래 투자를 정산하고, payday 주기마다 급여를 지급한다.
 *
 * 엔진은 "부하를 만드는 클라이언트"일 뿐, 돈의 이동은 전부 UseCase(은행 백엔드)가 처리한다.
 */
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

    // 가맹점별 결제 품목
    private static final Map<String, List<String>> ITEMS = Map.of(
            "쿠팡", List.of("생필품 로켓배송", "생수 2L x 12", "휴지 30롤", "무선 이어폰", "캠핑 의자"),
            "무신사", List.of("후드티", "청바지", "운동화", "패딩 조끼", "양말 5족 세트"),
            "배달의민족", List.of("치킨 한 마리", "떡볶이 세트", "마라탕", "족발 중(中)", "새벽 피자"),
            "넷플릭스", List.of("프리미엄 요금제", "스탠다드 요금제"),
            "KT 통신", List.of("휴대폰 요금", "인터넷 요금", "데이터 추가 충전")
    );

    private final TransferUseCase transferUseCase;
    private final SpendUseCase spendUseCase;
    private final InvestUseCase investUseCase;
    private final RefundUseCase refundUseCase;
    private final AgentUseCase agentUseCase;
    private final SimulationSetupUseCase setupUseCase;
    private final FeedSseService feedSseService;

    private final AtomicLong tickIntervalMs = new AtomicLong(1000);
    private final AtomicLong tickCount = new AtomicLong(0);
    private final long paydayIntervalTicks;
    private volatile long lastTickAt = 0;
    private volatile State state;

    public SimulationEngine(TransferUseCase transferUseCase, SpendUseCase spendUseCase,
                            InvestUseCase investUseCase, RefundUseCase refundUseCase,
                            AgentUseCase agentUseCase, SimulationSetupUseCase setupUseCase,
                            FeedSseService feedSseService,
                            @Value("${app.simulation.auto-start:true}") boolean autoStart,
                            @Value("${app.simulation.payday-interval-ticks:10}") long paydayIntervalTicks) {
        this.transferUseCase = transferUseCase;
        this.spendUseCase = spendUseCase;
        this.investUseCase = investUseCase;
        this.refundUseCase = refundUseCase;
        this.agentUseCase = agentUseCase;
        this.setupUseCase = setupUseCase;
        this.feedSseService = feedSseService;
        this.paydayIntervalTicks = Math.max(1, paydayIntervalTicks);
        this.state = autoStart ? State.RUNNING : State.STOPPED;
    }

    public void setTickIntervalMs(long ms) { tickIntervalMs.set(Math.max(1, Math.min(5000, ms))); }
    public long getTickIntervalMs()        { return tickIntervalMs.get(); }
    public State getState()                { return state; }
    public long getTickCount()             { return tickCount.get(); }

    public void start()  { state = State.RUNNING; }
    public void pause()  { state = State.PAUSED; }
    public void stop()   { state = State.STOPPED; }

    /** 정지 → 진행 중인 틱이 끝나길 기다린 뒤(synchronized) 거래/원장 초기화 */
    public synchronized void reset() {
        state = State.STOPPED;
        setupUseCase.reset();
        tickCount.set(0);
    }

    // 5ms 마다 기반 틱 — 실제 간격은 tickIntervalMs 로 조절 (최대 1ms까지 지원)
    @Scheduled(fixedRate = 5)
    public void tick() {
        if (state != State.RUNNING) return;
        long now = System.currentTimeMillis();
        if (now - lastTickAt < tickIntervalMs.get()) return;
        lastTickAt = now;
        runTick();
    }

    /** 틱 1회 실행 (테스트에서 직접 호출 가능) */
    public synchronized void runTick() {
        long tick = tickCount.incrementAndGet();
        List<Agent> agents = agentUseCase.findAll();
        if (agents.size() < 2) return;
        List<Account> merchants = spendUseCase.merchants();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (Agent agent : agents) {
            if (rnd.nextDouble() < agent.getChanceSpend() && !merchants.isEmpty()) {
                doSpend(agent, merchants, rnd);
            }
            if (rnd.nextDouble() < agent.getChancePay()) {
                doTransfer(agent, agents, rnd);
            }
            if (rnd.nextDouble() < agent.getChanceInvest()) {
                doInvest(agent, tick, rnd);
            }
            if (rnd.nextDouble() < agent.getChanceRefund()) {
                doRefund(agent);
            }
        }

        // 만기 도래 투자 정산
        investUseCase.settleMatured(tick).forEach(this::publish);

        // 월급날
        if (tick % paydayIntervalTicks == 0) {
            try {
                agentUseCase.paySalary().forEach(this::publish);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void doSpend(Agent agent, List<Account> merchants, ThreadLocalRandom rnd) {
        Account merchant = merchants.get(rnd.nextInt(merchants.size()));
        // 잔액의 1~3%, 100원 단위, 최소 1,000원
        BigDecimal amount = ratioOf(currentBalance(agent), 0.01 + rnd.nextDouble() * 0.02, -2);
        if (amount.compareTo(BigDecimal.valueOf(1000)) < 0) return;
        List<String> items = ITEMS.getOrDefault(merchant.getName(), List.of("결제"));
        String description = merchant.getName() + " · " + items.get(rnd.nextInt(items.size()));
        try {
            publish(spendUseCase.spend(agent.getAccount().getId(), merchant.getId(), amount, description));
        } catch (RuntimeException ignored) {
            // 잔액 부족 등 정상 실패
        }
        
    }

    private void doTransfer(Agent agent, List<Agent> agents, ThreadLocalRandom rnd) {
        List<Agent> others = agents.stream().filter(a -> !a.getId().equals(agent.getId())).toList();
        Agent target = others.get(rnd.nextInt(others.size()));
        // 잔액의 3~20%, 만원 단위
        BigDecimal amount = ratioOf(currentBalance(agent), 0.03 + rnd.nextDouble() * 0.17, -4);
        if (amount.signum() <= 0) return;
        String reason = REASONS.get(rnd.nextInt(REASONS.size()));
        try {
            publish(transferUseCase.transfer(agent.getAccount().getId(), target.getAccount().getId(), amount, reason));
        } catch (RuntimeException ignored) {
        }
    }

    private void doInvest(Agent agent, long tick, ThreadLocalRandom rnd) {
        // 잔액의 5~15%, 만원 단위, 만기 5~15틱
        BigDecimal amount = ratioOf(currentBalance(agent), 0.05 + rnd.nextDouble() * 0.10, -4);
        if (amount.signum() <= 0) return;
        long hold = 5 + rnd.nextInt(11);
        try {
            publish(investUseCase.invest(agent.getAccount().getId(), amount, tick, hold).transfer());
        } catch (RuntimeException ignored) {
        }
    }

    private void doRefund(Agent agent) {
        try {
            Transfer refund = refundUseCase.refundLatest(agent.getAccount().getId());
            if (refund != null) publish(refund);
        } catch (RuntimeException ignored) {
        }
    }

    /** 틱 시작 시 읽은 잔액은 같은 틱의 앞선 거래로 바뀌었을 수 있으므로 계좌 엔티티 기준 근사치로 사용 (최종 검증은 UseCase가 락 안에서 한다) */
    private BigDecimal currentBalance(Agent agent) {
        return agent.getAccount().getBalance();
    }

    private static BigDecimal ratioOf(BigDecimal balance, double ratio, int scale) {
        return balance.multiply(BigDecimal.valueOf(ratio))
                .setScale(scale, RoundingMode.DOWN)
                .setScale(0, RoundingMode.DOWN);
    }

    private void publish(Transfer t) {
        if (t == null) return;
        feedSseService.publish(FeedItem.from(t).toJson());
    }
}
