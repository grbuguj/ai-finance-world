package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.application.port.out.FaultInjectionPort;
import com.aifinance.application.service.OutboxRelayService;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.TransferUseCase;
import com.aifinance.domain.account.Account;
import com.aifinance.domain.account.InsufficientBalanceException;
import com.aifinance.external.settlement.SettlementInbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transactional Outbox 신뢰성 검증.
 *
 * 핵심 주장: "원장에 커밋된 거래는 브로커 장애·ACK 유실이 있어도 외부 정산 장부에 정확히 한 번 기록된다."
 *  - 유실 0: 커밋된 거래 수 = 정산 장부 행 수
 *  - 중복 0: 정산 장부의 거래 id 는 유일 (중복 도착은 멱등 소비자가 걸러냄)
 *  - 롤백된 거래는 이벤트도 없음
 */
@SpringBootTest(classes = AiFinanceWorldApplication.class)
class OutboxReliabilityTest {

    @Autowired SimulationEngine engine;
    @Autowired AgentUseCase agentUseCase;
    @Autowired TransferUseCase transferUseCase;
    @Autowired OutboxRelayService relay;
    @Autowired FaultInjectionPort faults;
    @Autowired SettlementInbox inbox;
    @Autowired JdbcTemplate jdbc;

    Account a;
    Account b;

    @BeforeEach
    void setUp() {
        faults.setBrokerDown(false);
        faults.loseNextAcks(0);
        engine.reset();
        relay.drain(100); // 초기 자본금(OPENING) 이벤트 정리
        assertEquals(0, outboxPending(), () -> "초기 이벤트 발행 실패: " + diag());
        List<Account> accounts = agentUseCase.findAll().stream().map(ag -> ag.getAccount()).toList();
        a = accounts.get(0);
        b = accounts.get(1);
    }

    @AfterEach
    void tearDown() {
        faults.setBrokerDown(false);
        faults.loseNextAcks(0);
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private long transfers()        { return count("select count(*) from transfers"); }
    private long settled()          { return count("select count(*) from settlement_records"); }
    private long outboxTotal()      { return count("select count(*) from outbox_events"); }
    private long outboxPending()    { return count("select count(*) from outbox_events where status = 'PENDING'"); }

    /** 실패 진단: 발행되지 못한 이벤트의 상태와 마지막 오류 */
    private String diag() {
        return jdbc.queryForList("select status, attempts, last_error from outbox_events where status <> 'PUBLISHED' order by id limit 3")
                .toString();
    }

    @Test
    void 브로커_장애_중에도_거래는_성공하고_복구_후_유실없이_전량_전달된다() {
        faults.setBrokerDown(true);

        for (int i = 0; i < 30; i++) {
            transferUseCase.transfer(a.getId(), b.getId(), BigDecimal.valueOf(1_000), "장애 중 송금 " + i);
        }
        OutboxRelayService.RelayResult duringOutage = relay.relayOnce();

        // 장애 중: 거래는 커밋됐고, 이벤트는 유실되지 않고 Outbox 에 대기
        assertEquals(0, duringOutage.published());
        assertEquals(1, duringOutage.failed(), "첫 실패에서 배치를 멈춰야 한다 (순서 보장)");
        assertEquals(30, outboxPending());
        assertEquals(transfers() - 30, settled(), "장애 중에는 새 거래가 정산 장부에 도착하지 않는다");

        // 복구: 백오프 대기 중인 첫 이벤트까지 포함해 모두 발행되도록 재시도 시각을 당긴다
        faults.setBrokerDown(false);
        jdbc.update("update outbox_events set next_attempt_at = created_at where status = 'PENDING'");
        relay.drain(100);

        assertEquals(0, outboxPending());
        assertEquals(transfers(), settled(), "커밋된 거래 수 = 정산 장부 행 수 (유실 0)");
        assertEquals(transfers(), outboxTotal(), "거래 1건당 이벤트 1건");
    }

    @Test
    void ACK_유실로_같은_이벤트가_재발행돼도_정산은_한_번만_된다() {
        for (int i = 0; i < 10; i++) {
            transferUseCase.transfer(a.getId(), b.getId(), BigDecimal.valueOf(500), "ACK 유실 테스트 " + i);
        }
        long duplicatesBefore = inbox.duplicateCount();

        faults.loseNextAcks(3); // 브로커는 받았지만 응답이 사라지는 상황 3회
        for (int round = 0; round < 10 && outboxPending() > 0; round++) {
            jdbc.update("update outbox_events set next_attempt_at = created_at where status = 'PENDING'");
            relay.relayOnce();
        }

        assertEquals(0, outboxPending());
        assertEquals(3, inbox.duplicateCount() - duplicatesBefore, "재발행된 3건은 중복으로 걸러져야 한다");
        assertEquals(transfers(), settled(), "중복 도착에도 정산 장부는 거래당 1행");
        assertEquals(3L, count("select coalesce(sum(delivery_count - 1), 0) from settlement_records"));
    }

    @Test
    void 롤백된_거래는_Outbox_에도_남지_않는다() {
        long outboxBefore = outboxTotal();
        BigDecimal tooMuch = BigDecimal.valueOf(1_000_000_000L);

        assertThrows(InsufficientBalanceException.class,
                () -> transferUseCase.transfer(a.getId(), b.getId(), tooMuch, "잔액 초과"));

        assertEquals(outboxBefore, outboxTotal(), "롤백된 거래의 이벤트가 새어 나가면 안 된다");
        assertEquals(transfers(), outboxTotal());
    }

    @Test
    void 동시_송금_200건_후에도_거래와_이벤트가_1대1로_대응한다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 200; i++) {
                boolean forward = i % 2 == 0;
                futures.add(pool.submit(() -> forward
                        ? transferUseCase.transfer(a.getId(), b.getId(), BigDecimal.valueOf(100), "동시 송금")
                        : transferUseCase.transfer(b.getId(), a.getId(), BigDecimal.valueOf(100), "동시 송금")));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        relay.drain(100);

        assertEquals(transfers(), outboxTotal());
        assertEquals(0, outboxPending());
        assertEquals(transfers(), settled());
        assertEquals(0L, count("""
                select count(*) from transfers t
                left join outbox_events o on o.aggregate_id = t.id
                where o.id is null"""), "이벤트가 없는 거래가 있으면 안 된다");
    }

    @Test
    void 최대_재시도를_넘긴_이벤트는_DEAD_로_격리되고_수동_재처리할_수_있다() {
        transferUseCase.transfer(a.getId(), b.getId(), BigDecimal.valueOf(700), "DEAD 테스트");
        faults.setBrokerDown(true);

        for (int i = 0; i < 20 && count("select count(*) from outbox_events where status = 'DEAD'") == 0; i++) {
            jdbc.update("update outbox_events set next_attempt_at = created_at where status = 'PENDING'");
            relay.relayOnce();
        }
        assertEquals(1L, count("select count(*) from outbox_events where status = 'DEAD'"));

        faults.setBrokerDown(false);
        assertEquals(1, relay.requeueDead());
        relay.drain(10);

        assertEquals(0L, count("select count(*) from outbox_events where status <> 'PUBLISHED'"));
        assertEquals(transfers(), settled());
    }
}
