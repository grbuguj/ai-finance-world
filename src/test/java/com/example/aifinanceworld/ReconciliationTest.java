package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.application.port.out.FaultInjectionPort;
import com.aifinance.application.service.OutboxRelayService;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.ReconciliationUseCase;
import com.aifinance.application.usecase.ReconciliationUseCase.RunView;
import com.aifinance.application.usecase.TransferUseCase;
import com.aifinance.domain.account.Account;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 일마감 대사 검증 — 대사가 "맞을 때 맞다고, 틀릴 때 정확히 어디가 틀렸는지" 말하는지 확인한다.
 * (일부러 정산 장부를 망가뜨려 각 유형의 차이를 정확히 잡아내는지 본다)
 */
@SpringBootTest(classes = AiFinanceWorldApplication.class)
class ReconciliationTest {

    @Autowired SimulationEngine engine;
    @Autowired AgentUseCase agentUseCase;
    @Autowired TransferUseCase transferUseCase;
    @Autowired OutboxRelayService relay;
    @Autowired ReconciliationUseCase reconciliation;
    @Autowired FaultInjectionPort faults;
    @Autowired JdbcTemplate jdbc;

    Account a;
    Account b;

    @BeforeEach
    void setUp() {
        faults.setBrokerDown(false);
        faults.loseNextAcks(0);
        engine.reset();
        List<Account> accounts = agentUseCase.findAll().stream().map(ag -> ag.getAccount()).toList();
        a = accounts.get(0);
        b = accounts.get(1);
    }

    @AfterEach
    void tearDown() {
        faults.setBrokerDown(false);
    }

    private void transfers(int n) {
        for (int i = 0; i < n; i++) {
            transferUseCase.transfer(a.getId(), b.getId(), BigDecimal.valueOf(1_000), "대사 테스트 " + i);
        }
    }

    @Test
    void 전량_전달되면_MATCHED() {
        transfers(20);
        relay.drain(100);

        RunView run = reconciliation.reconcileToday();

        assertEquals("MATCHED", run.status());
        assertEquals(run.bankCount(), run.settlementCount());
        assertEquals(0, run.bankAmount().compareTo(run.settlementAmount()));
        assertTrue(run.ledgerBalanced());
        assertTrue(run.breaks().isEmpty());
    }

    @Test
    void 브로커_장애로_전달_중인_건은_오탐하지_않고_PENDING_IN_FLIGHT() {
        relay.drain(100);
        faults.setBrokerDown(true);
        transfers(5);
        relay.relayOnce();

        RunView run = reconciliation.reconcileToday();

        assertEquals("PENDING_IN_FLIGHT", run.status());
        assertEquals(5, run.inFlightCount());
        assertEquals(0, run.missingCount(), "Outbox 에 남아 있는 건은 유실이 아니다");

        // 복구 후 재대사하면 일치
        faults.setBrokerDown(false);
        jdbc.update("update outbox_events set next_attempt_at = created_at where status = 'PENDING'");
        relay.drain(100);
        assertEquals("MATCHED", reconciliation.reconcileToday().status());
    }

    @Test
    void 정산_장부의_누락_불명_금액불일치를_각각_정확히_잡아낸다() {
        transfers(10);
        relay.drain(100);
        List<Long> ids = jdbc.queryForList(
                "select transfer_id from settlement_records where tx_type = 'TRANSFER' order by transfer_id", Long.class);

        // 정산 시스템 쪽 사고를 재현
        jdbc.update("delete from settlement_records where transfer_id = ?", ids.get(0));                 // 누락
        jdbc.update("update settlement_records set amount = amount + 1 where transfer_id = ?", ids.get(1)); // 금액 불일치
        jdbc.update("""
                insert into settlement_records (transfer_id, tx_type, sender_account_id, receiver_account_id,
                                                amount, occurred_at, received_at, delivery_count)
                values (999999, 'TRANSFER', 1, 2, 1000, current_timestamp, current_timestamp, 1)""");       // 불명

        RunView run = reconciliation.reconcileToday();

        assertEquals("UNMATCHED", run.status());
        assertEquals(1, run.missingCount());
        assertEquals(1, run.mismatchCount());
        assertEquals(1, run.unexpectedCount());
        assertTrue(run.breaks().stream().anyMatch(x -> x.kind().equals("MISSING_IN_SETTLEMENT") && x.transferId().equals(ids.get(0))));
        assertTrue(run.breaks().stream().anyMatch(x -> x.kind().equals("AMOUNT_MISMATCH") && x.transferId().equals(ids.get(1))));
        assertTrue(run.breaks().stream().anyMatch(x -> x.kind().equals("UNEXPECTED_IN_SETTLEMENT") && x.transferId() == 999999L));
        assertTrue(run.ledgerBalanced(), "정산 장부 사고는 은행 원장 무결성과 무관해야 한다");
    }

    @Test
    void 대사_결과는_이력으로_남는다() {
        transfers(3);
        relay.drain(100);
        reconciliation.reconcileToday();
        reconciliation.reconcileToday();

        assertEquals(2, reconciliation.recent().size());
        assertNotNull(reconciliation.latest());
    }
}
