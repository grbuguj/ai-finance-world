package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.adapter.in.web.OperationsController;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.BalanceRebuildUseCase.RebuildResult;
import com.aifinance.application.usecase.IntegrityUseCase;
import com.aifinance.application.usecase.IntegrityUseCase.IntegrityReport;
import com.aifinance.application.usecase.TransferUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 잔액 재구성: 잔액을 일부러 틀리게 만든다 → 무결성 검사가 잡는다 → dry-run 이 차이를 보고 → apply 후 balanced. */
@SpringBootTest(classes = AiFinanceWorldApplication.class)
class BalanceRebuildTest {

    @Autowired SimulationEngine engine;
    @Autowired AgentUseCase agentUseCase;
    @Autowired TransferUseCase transferUseCase;
    @Autowired IntegrityUseCase integrityUseCase;
    @Autowired OperationsController ops;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 틀린_잔액을_감지_보고_교정한다() {
        engine.reset();
        List<Long> ids = agentUseCase.findAll().stream().map(a -> a.getAccount().getId()).toList();
        for (int i = 0; i < 20; i++) {
            transferUseCase.transfer(ids.get(0), ids.get(1), BigDecimal.valueOf(1_000), "재구성 테스트 " + i);
        }
        assertTrue(integrityUseCase.check().balanced());

        // 직접 SQL 로 잔액 하나를 +7원 틀리게 만든다 (앱을 우회한 사고)
        Long victim = ids.get(2);
        BigDecimal real = jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, victim);
        jdbc.update("update accounts set balance = balance + 7 where id = ?", victim);

        IntegrityReport broken = integrityUseCase.check();
        assertFalse(broken.balanced());
        assertEquals(1, broken.mismatchedAccountCount());

        // dry-run: 차이만 보고하고 아무것도 바꾸지 않는다
        RebuildResult dry = (RebuildResult) ops.rebuildBalances(false).getBody();
        assertFalse(dry.applied());
        assertEquals(1, dry.mismatchedFound());
        assertEquals(victim, dry.diffs().get(0).accountId());
        assertEquals(0, dry.diffs().get(0).difference().compareTo(BigDecimal.valueOf(-7)));
        assertFalse(integrityUseCase.check().balanced(), "dry-run 은 교정하지 않는다");

        // 실행 중에는 apply 거부
        engine.start();
        try {
            ResponseEntity<?> rejected = ops.rebuildBalances(true);
            assertEquals(409, rejected.getStatusCode().value());
        } finally {
            engine.pause();
        }
        assertFalse(integrityUseCase.check().balanced());

        // 정지 상태에서 apply → 교정
        RebuildResult applied = (RebuildResult) ops.rebuildBalances(true).getBody();
        assertTrue(applied.applied());
        assertEquals(1, applied.corrected());
        assertEquals(0, jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, victim).compareTo(real));
        assertTrue(integrityUseCase.check().balanced());

        RebuildResult again = (RebuildResult) ops.rebuildBalances(false).getBody();
        assertEquals(0, again.mismatchedFound());
    }
}
