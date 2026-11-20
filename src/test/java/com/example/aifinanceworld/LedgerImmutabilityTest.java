package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.IntegrityUseCase;
import com.aifinance.application.usecase.RefundUseCase;
import com.aifinance.application.usecase.SpendUseCase;
import com.aifinance.domain.account.AccountType;
import com.aifinance.domain.transfer.Transfer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 정정은 반대 분개로만 한다 — 환불이 원본 거래·원본 분개를 한 글자도 건드리지 않고 새 행만 추가함을 고정한다.
 * (원장 불변성 실험: docs/experiments/ledger-immutability.md)
 */
@SpringBootTest(classes = AiFinanceWorldApplication.class)
class LedgerImmutabilityTest {

    @Autowired SimulationEngine engine;
    @Autowired AgentUseCase agentUseCase;
    @Autowired AccountPort accountPort;
    @Autowired SpendUseCase spendUseCase;
    @Autowired RefundUseCase refundUseCase;
    @Autowired IntegrityUseCase integrityUseCase;
    @Autowired JdbcTemplate jdbc;

    private String transferRow(long id) {
        return jdbc.queryForObject("""
                select concat_ws('|', id, sender_id, receiver_id, amount, type, tx_type, description,
                                 coalesce(original_transfer_id, -1), created_at, occurred_at)
                from transfers where id = ?""", String.class, id);
    }

    private List<String> ledgerRows(long transferId) {
        return jdbc.queryForList("""
                select concat_ws('|', id, account_id, transfer_id, type, amount, created_at)
                from ledger_entries where transfer_id = ? order by id""", String.class, transferId);
    }

    @Test
    void 환불은_원본_거래와_원본_분개를_바꾸지_않고_반대_분개만_추가한다() {
        engine.reset();
        Long agentId = agentUseCase.findAll().get(0).getAccount().getId();
        Long merchantId = accountPort.findByType(AccountType.MERCHANT).get(0).getId();
        Transfer spend = spendUseCase.spend(agentId, merchantId, new BigDecimal("70000"), "불변성 테스트");

        String transferBefore = transferRow(spend.getId());
        List<String> ledgerBefore = ledgerRows(spend.getId());
        long transfersBefore = jdbc.queryForObject("select count(*) from transfers", Long.class);
        long ledgerCountBefore = jdbc.queryForObject("select count(*) from ledger_entries", Long.class);
        assertEquals(2, ledgerBefore.size());

        Transfer refund = refundUseCase.refund(spend.getId());

        assertEquals(transferBefore, transferRow(spend.getId()), "원본 거래 행은 그대로여야 한다");
        assertEquals(ledgerBefore, ledgerRows(spend.getId()), "원본 분개는 그대로여야 한다");
        assertEquals(transfersBefore + 1, jdbc.queryForObject("select count(*) from transfers", Long.class));
        assertEquals(ledgerCountBefore + 2, jdbc.queryForObject("select count(*) from ledger_entries", Long.class));
        assertEquals(spend.getId(), refund.getOriginalTransferId());

        // 반대 분개: 원본에서 DEBIT 이던 계좌(에이전트)가 환불에서는 CREDIT
        String debitAccountOfSpend = jdbc.queryForObject(
                "select account_id from ledger_entries where transfer_id = ? and type = 'DEBIT'", String.class, spend.getId());
        String creditAccountOfRefund = jdbc.queryForObject(
                "select account_id from ledger_entries where transfer_id = ? and type = 'CREDIT'", String.class, refund.getId());
        assertEquals(debitAccountOfSpend, creditAccountOfRefund);
        assertTrue(integrityUseCase.check().balanced());
    }

    @Test
    void 이중_환불은_애플리케이션과_DB_유니크_제약_양쪽에서_막힌다() {
        engine.reset();
        Long agentId = agentUseCase.findAll().get(0).getAccount().getId();
        Long merchantId = accountPort.findByType(AccountType.MERCHANT).get(0).getId();
        Transfer spend = spendUseCase.spend(agentId, merchantId, new BigDecimal("1000"), "이중 환불 테스트");
        refundUseCase.refund(spend.getId());

        assertThrows(IllegalStateException.class, () -> refundUseCase.refund(spend.getId()), "앱 수준: 이미 환불된 거래");

        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into transfers (sender_id, receiver_id, amount, type, tx_type, original_transfer_id, created_at, occurred_at)
                values (?, ?, 1000, 'SUCCESS', 'REFUND', ?, ?, ?)""", merchantId, agentId, spend.getId(), now, now),
                "DB 수준: uk_transfers_original 이 같은 원거래의 두 번째 정정을 거부");
        assertTrue(integrityUseCase.check().balanced());
    }

    @Test
    void 환불_후보는_이미_환불된_소비를_제외한다() {
        engine.reset();
        Long agentId = agentUseCase.findAll().get(0).getAccount().getId();
        Long merchantId = accountPort.findByType(AccountType.MERCHANT).get(0).getId();
        Transfer first = spendUseCase.spend(agentId, merchantId, new BigDecimal("1000"), "첫째");
        Transfer second = spendUseCase.spend(agentId, merchantId, new BigDecimal("2000"), "둘째");

        Transfer refunded = refundUseCase.refundLatest(agentId); // 가장 최근 = 둘째
        assertEquals(second.getId(), refunded.getOriginalTransferId());
        Transfer next = refundUseCase.refundLatest(agentId); // 둘째는 제외되고 첫째
        assertEquals(first.getId(), next.getOriginalTransferId());
        assertNull(refundUseCase.refundLatest(agentId), "환불할 소비가 더 없다");
    }
}
