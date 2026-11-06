package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.application.port.out.BalanceCachePort;
import com.aifinance.application.port.out.TransactionEventPort;
import com.aifinance.application.service.OutboxRelayService;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.ReconciliationUseCase;
import com.aifinance.application.usecase.TransferUseCase;
import com.aifinance.domain.account.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 운영과 같은 인프라(MySQL + Kafka + Redis 컨테이너)에서 Outbox 전 구간을 검증한다.
 *
 *   송금 → outbox_events → 릴레이 → 실제 Kafka 브로커 → @KafkaListener(외부 정산) → settlement_records
 *
 * 장애는 애플리케이션 수준 흉내(장애 주입기)가 아니라 Kafka 컨테이너를 실제로 일시정지(docker pause)해서 만든다.
 */
@Import({MySqlContainerConfig.class, InfraContainersConfig.class})
@ActiveProfiles({"mysqltest", "infra"})
@SpringBootTest(classes = AiFinanceWorldApplication.class, properties = {
        "spring.autoconfigure.exclude=",   // test 설정의 Kafka/Redis 자동구성 제외를 해제
        "app.outbox.relay.auto=false",
        "app.kafka.send-timeout-ms=3000",
        "app.outbox.relay.max-attempts=1000"   // 브로커 재가동을 기다리는 동안 DEAD 로 격리되지 않게
})
class FullInfraOutboxIntegrationTest {

    @Autowired SimulationEngine engine;
    @Autowired AgentUseCase agentUseCase;
    @Autowired TransferUseCase transferUseCase;
    @Autowired OutboxRelayService relay;
    @Autowired ReconciliationUseCase reconciliation;
    @Autowired BalanceCachePort balanceCache;
    @Autowired TransactionEventPort eventPort;
    @Autowired KafkaContainer kafka;
    @Autowired JdbcTemplate jdbc;

    Account a;
    Account b;

    @BeforeEach
    void setUp() {
        engine.reset();
        List<Account> accounts = agentUseCase.findAll().stream().map(ag -> ag.getAccount()).toList();
        a = accounts.get(0);
        b = accounts.get(1);
        publishAllPending(60_000);
        waitUntil(() -> settled() == transfers(), 60_000, "초기 자본금 이벤트가 정산 시스템에 도착해야 함");
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private long transfers() { return count("select count(*) from transfers"); }
    private long settled()   { return count("select count(*) from settlement_records"); }
    private long pending()   { return count("select count(*) from outbox_events where status = 'PENDING'"); }

    /** 백오프를 기다리지 않도록 재시도 시각을 당기며, 대기 이벤트가 모두 발행될 때까지 릴레이를 돌린다 */
    private void publishAllPending(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (pending() > 0) {
            if (System.currentTimeMillis() > deadline) {
                fail("Outbox 이벤트 발행 실패: " + jdbc.queryForList(
                        "select status, attempts, last_error from outbox_events where status <> 'PUBLISHED' limit 3"));
            }
            jdbc.update("update outbox_events set next_attempt_at = created_at where status = 'PENDING'");
            relay.relayOnce();
        }
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMs, String message) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail(message);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
    }

    @Test
    void 실제_Kafka_를_거쳐_정산_시스템까지_전량_도착한다() {
        assertTrue(eventPort.getClass().getSimpleName().contains("Kafka"), "Kafka 어댑터가 활성화되어야 함: " + eventPort.getClass());

        for (int i = 0; i < 30; i++) {
            transferUseCase.transfer(a.getId(), b.getId(), BigDecimal.valueOf(1_000), "Kafka 통합 " + i);
        }
        publishAllPending(30_000);

        waitUntil(() -> settled() == transfers(), 30_000, "정산 장부 행 수가 거래 수와 같아져야 함");
        assertEquals(0L, count("select count(*) from settlement_records where delivery_count > 1"),
                "정상 경로에서는 중복 전달이 없어야 함");
        assertEquals("MATCHED", reconciliation.reconcileToday().status());
    }

    @Test
    void Kafka_브로커를_실제로_멈춰도_거래는_성공하고_재가동_후_유실없이_전달된다() {
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            for (int i = 0; i < 10; i++) {
                transferUseCase.transfer(a.getId(), b.getId(), BigDecimal.valueOf(500), "브로커 정지 중 " + i);
            }
            OutboxRelayService.RelayResult r = relay.relayOnce();
            assertEquals(0, r.published(), "브로커가 멈춘 동안에는 발행되지 않아야 함");
            assertEquals(10, pending(), "이벤트는 유실되지 않고 Outbox 에 대기해야 함");
            assertEquals("PENDING_IN_FLIGHT", reconciliation.reconcileToday().status(), "대사는 전달 중으로 판단해야 함");
        } finally {
            kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
        }

        publishAllPending(60_000);
        waitUntil(() -> settled() == transfers(), 60_000, "브로커 재가동 후 정산 장부가 거래 수를 따라잡아야 함");
        assertEquals("MATCHED", reconciliation.reconcileToday().status());
    }

    @Test
    void Redis_잔액_캐시는_커밋된_잔액과_같다() {
        transferUseCase.transfer(a.getId(), b.getId(), BigDecimal.valueOf(777), "캐시 확인");
        BigDecimal dbBalance = jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, b.getId());

        assertTrue(balanceCache.getClass().getSimpleName().contains("Redis"), "Redis 캐시 어댑터가 활성화되어야 함");
        assertEquals(0, balanceCache.get(b.getId()).orElseThrow().compareTo(dbBalance));
    }
}
