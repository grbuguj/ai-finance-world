package com.aifinance.adapter.in.scheduler;

import com.aifinance.adapter.in.web.FeedSseService;
import com.aifinance.adapter.in.web.dto.FeedItem;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.InvestUseCase;
import com.aifinance.application.usecase.RefundUseCase;
import com.aifinance.application.usecase.SimulationSetupUseCase;
import com.aifinance.application.usecase.SpendUseCase;
import com.aifinance.application.usecase.TransferUseCase;
import com.aifinance.application.service.EndOfDayService;
import com.aifinance.application.service.VirtualClock;
import com.aifinance.domain.account.Account;
import com.aifinance.domain.agent.Agent;
import com.aifinance.domain.transfer.Transfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    /** 자정(00:00) 이후 이 시각을 지나는 틱에서 전날 대사를 자동 실행한다 (자정 직전 거래의 전달 지연 흡수) */
    private static final LocalTime VIRTUAL_EOD_GRACE = LocalTime.of(0, 10);

    /**
     * SETUP: 월드 미생성, 사용자가 에이전트 수를 고르길 대기 (setup() 호출 전).
     * RUNNING/PAUSED: 월드가 있고 틱이 돌거나 일시정지됨.
     * ENDED: 정지(= 종료). 더 이상 틱이 돌지 않으며, 종료 요약만 조회 가능. "새 시뮬레이션"으로만 SETUP 복귀.
     */
    public enum State { SETUP, RUNNING, PAUSED, ENDED }

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
    private final VirtualClock virtualClock;
    private final EndOfDayService endOfDayService;

    private final AtomicLong tickIntervalMs = new AtomicLong(1000);
    // 완료된 가상 하루(일마감 대사 통과) 수
    private final AtomicLong virtualDaysElapsed = new AtomicLong(0);
    private final long paydayIntervalTicks;
    private volatile long lastTickAt = 0;
    private volatile State state;
    // 자동 일마감이 이미 처리한 마지막 영업일 — 같은 날 중복 실행 방지
    private volatile LocalDate lastEodBusinessDate = null;

    public SimulationEngine(TransferUseCase transferUseCase, SpendUseCase spendUseCase,
                            InvestUseCase investUseCase, RefundUseCase refundUseCase,
                            AgentUseCase agentUseCase, SimulationSetupUseCase setupUseCase,
                            FeedSseService feedSseService, VirtualClock virtualClock,
                            EndOfDayService endOfDayService,
                            @Value("${app.simulation.auto-start:true}") boolean autoStart,
                            @Value("${app.simulation.bootstrap:false}") boolean bootstrap,
                            @Value("${app.simulation.payday-interval-ticks:10}") long paydayIntervalTicks) {
        this.transferUseCase = transferUseCase;
        this.spendUseCase = spendUseCase;
        this.investUseCase = investUseCase;
        this.refundUseCase = refundUseCase;
        this.agentUseCase = agentUseCase;
        this.setupUseCase = setupUseCase;
        this.feedSseService = feedSseService;
        this.virtualClock = virtualClock;
        this.endOfDayService = endOfDayService;
        this.paydayIntervalTicks = Math.max(1, paydayIntervalTicks);
        // bootstrap=false (기본, 대시보드) → 월드가 아직 없으므로 SETUP 에서 대기.
        // bootstrap=true (테스트/부하테스트) → DataInitializer 가 기동 시 이미 월드를 만들었다는 전제로 기존처럼 동작.
        this.state = !bootstrap ? State.SETUP : (autoStart ? State.RUNNING : State.PAUSED);
    }

    public void setTickIntervalMs(long ms) { tickIntervalMs.set(Math.max(1, Math.min(5000, ms))); }
    public long getTickIntervalMs()        { return tickIntervalMs.get(); }
    public State getState()                { return state; }
    public long getTickCount()             { return virtualClock.currentTick(); }
    public long getVirtualDaysElapsed()    { return virtualDaysElapsed.get(); }
    public LocalDateTime getVirtualNow()   { return virtualClock.now(); }
    public long getMinutesPerTick()        { return virtualClock.minutesPerTick(); }
    public LocalDateTime getVirtualStart() { return virtualClock.at(0); }
    /** 가상 며칠째인지 (시작일 = 1) */
    public long getVirtualDay() {
        return java.time.temporal.ChronoUnit.DAYS.between(virtualClock.startDate(), virtualClock.now().toLocalDate()) + 1;
    }

    /** 설정 화면 → "시작": 고른 에이전트 수로 월드를 만들고 바로 RUNNING 으로 전환 */
    public synchronized void setup(int agentCount) {
        if (state != State.SETUP) {
            throw new IllegalStateException("이미 설정이 끝났습니다 (현재 상태: " + state + ")");
        }
        setupUseCase.initialize(agentCount);
        virtualClock.reset();
        lastEodBusinessDate = null;
        state = State.RUNNING;
    }

    public synchronized void start() {
        if (state == State.PAUSED) { state = State.RUNNING; return; }
        if (state == State.RUNNING) return; // 이미 실행 중 — 멱등
        throw new IllegalStateException(state == State.SETUP
                ? "먼저 /simulation/setup 으로 에이전트 수를 정해 주세요"
                : "종료된 시뮬레이션입니다. 새 시뮬레이션을 시작해 주세요");
    }

    public synchronized void pause() {
        if (state == State.RUNNING) state = State.PAUSED;
        else if (state != State.PAUSED) {
            throw new IllegalStateException("실행 중일 때만 일시정지할 수 있습니다 (현재 상태: " + state + ")");
        }
    }

    /** 정지 = 종료. 더 이상 틱이 돌지 않는다 (상태만 바꾼다 — 데이터는 /simulation/summary 조회를 위해 남긴다) */
    public synchronized void stop() {
        if (state == State.RUNNING || state == State.PAUSED) state = State.ENDED;
        else if (state != State.ENDED) {
            throw new IllegalStateException("설정 단계에서는 정지할 것이 없습니다");
        }
    }

    /** ENDED → "새 시뮬레이션": 거래/원장/에이전트까지 모두 지우고 SETUP 으로 복귀 (다시 /simulation/setup 호출 가능) */
    public synchronized void newSimulation() {
        if (state != State.ENDED) {
            throw new IllegalStateException("종료(ENDED) 상태에서만 새 시뮬레이션을 시작할 수 있습니다 (현재 상태: " + state + ")");
        }
        setupUseCase.resetAll();
        virtualClock.reset();
        virtualDaysElapsed.set(0);
        lastEodBusinessDate = null;
        state = State.SETUP;
    }

    /** 거래/원장 초기화 (에이전트는 유지) — 테스트가 깨끗한 상태에서 직접 틱을 돌리기 위해 상태와 무관하게 호출한다 */
    public synchronized void reset() {
        state = State.PAUSED;
        setupUseCase.reset();
        virtualClock.reset();
        virtualDaysElapsed.set(0);
        lastEodBusinessDate = null;
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
        long tick = virtualClock.advance();
        maybeRunVirtualEod();
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

    /**
     * 가상 시계가 새 가상 하루의 00:10 을 지나는 틱에서 직전 가상 하루에 대해 자동으로 일마감 대사를 실행한다.
     * 실제 시계 기반 @Scheduled(app.eod.cron) 은 시뮬레이션에서는 쓰지 않는다 (ADR-0006).
     * 브로커가 죽어 있어도 대사 자체는 그대로 실행된다 — Outbox 가 비지 않은 건은 PENDING_IN_FLIGHT 로 잡힐 뿐,
     * 오탐(누락/불일치) 없이 다음 대사에서 정상적으로 MATCHED 로 넘어간다.
     */
    private void maybeRunVirtualEod() {
        LocalDateTime virtualNow = virtualClock.now();
        if (virtualNow.toLocalTime().isBefore(VIRTUAL_EOD_GRACE)) return;
        LocalDate businessDate = virtualNow.toLocalDate().minusDays(1);
        if (businessDate.isBefore(virtualClock.startDate())) return; // 시작일 이전은 마감 대상이 아니다
        if (businessDate.equals(lastEodBusinessDate)) return; // 이미 그 영업일은 마감했다
        try {
            endOfDayService.closeBusinessDay(businessDate);
            lastEodBusinessDate = businessDate;
            virtualDaysElapsed.incrementAndGet();
        } catch (RuntimeException e) {
            log.warn("[가상 EOD] {} 대사 실행 실패 — 다음 틱에서 재시도", businessDate, e);
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
