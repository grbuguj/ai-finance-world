package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.IntegrityUseCase;
import com.aifinance.application.usecase.TransferUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DB 수준 원장 불변성 가드: 앱을 우회한 직접 UPDATE/DELETE 는 트리거가 막고, 시뮬레이션 초기화(reset)만 통과한다.
 */
@SpringBootTest(classes = AiFinanceWorldApplication.class)
@Import(MySqlContainerConfig.class)
@ActiveProfiles("mysqltest")
class MySqlLedgerGuardTest {

    @Autowired SimulationEngine engine;
    @Autowired AgentUseCase agentUseCase;
    @Autowired TransferUseCase transferUseCase;
    @Autowired IntegrityUseCase integrityUseCase;
    @Autowired JdbcTemplate jdbc;

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    @Test
    void 직접_UPDATE_DELETE_는_트리거가_막고_reset_만_통과한다() {
        engine.reset();
        List<Long> ids = agentUseCase.findAll().stream().map(a -> a.getAccount().getId()).toList();
        transferUseCase.transfer(ids.get(0), ids.get(1), BigDecimal.valueOf(1_000), "가드 테스트");
        long transfers = count("transfers"), ledger = count("ledger_entries");
        assertTrue(transfers > 0 && ledger > 0);
        String amountBefore = jdbc.queryForObject("select amount from ledger_entries order by id desc limit 1", String.class);

        assertThrows(DataAccessException.class, () -> jdbc.update("update ledger_entries set amount = 1"), "원장 UPDATE 차단");
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from ledger_entries"), "원장 DELETE 차단");
        assertThrows(DataAccessException.class, () -> jdbc.update("update transfers set description = 'x'"), "거래 UPDATE 차단");
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from transfers"), "거래 DELETE 차단");

        assertEquals(transfers, count("transfers"));
        assertEquals(ledger, count("ledger_entries"));
        assertEquals(amountBefore, jdbc.queryForObject("select amount from ledger_entries order by id desc limit 1", String.class));
        assertTrue(integrityUseCase.check().balanced());

        // 앱의 정상 경로(INSERT)와 시뮬레이션 초기화는 그대로 동작한다
        transferUseCase.transfer(ids.get(0), ids.get(1), BigDecimal.valueOf(500), "가드 이후에도 정상");
        engine.reset();
        assertEquals(10, count("transfers"), "reset 은 원장을 비우고 초기 자본금(OPENING 10건)만 다시 기록한다");

        // reset 이 끝나면 세션 변수가 꺼져 있어 다시 차단된다
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from ledger_entries"));
    }
}
