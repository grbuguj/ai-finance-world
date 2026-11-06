package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.adapter.in.web.TransferController;
import com.aifinance.adapter.in.web.dto.FeedItem;
import com.aifinance.application.port.out.TransferPort;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.TransferUseCase;
import com.aifinance.domain.account.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 거래 이력 API — 예전엔 GET /transfers 가 원장 전체를 한 번에 반환했다 (거래 50만 건이면 응답 하나에 50만 행).
 * 키셋 페이지네이션으로 바꾼 뒤: 페이지 크기 제한, 최신순, 페이지를 이어 붙이면 빠짐·중복 없이 전체와 같다.
 */
@SpringBootTest(classes = AiFinanceWorldApplication.class)
class TransferPaginationTest {

    @Autowired SimulationEngine engine;
    @Autowired AgentUseCase agentUseCase;
    @Autowired TransferUseCase transferUseCase;
    @Autowired TransferPort transferPort;
    @Autowired TransferController controller;

    @BeforeEach
    void setUp() {
        engine.reset();
        List<Account> accounts = agentUseCase.findAll().stream().map(ag -> ag.getAccount()).toList();
        for (int i = 0; i < 25; i++) {
            transferUseCase.transfer(accounts.get(0).getId(), accounts.get(1).getId(), new BigDecimal("100"), "page-" + i);
        }
    }

    @Test
    void 페이지를_이어_붙이면_빠짐도_중복도_없이_최신순이다() {
        long total = transferPort.count();
        List<Long> ids = new ArrayList<>();
        Long beforeId = null;
        while (true) {
            List<FeedItem> page = controller.getTransfers(7, beforeId);
            assertTrue(page.size() <= 7);
            if (page.isEmpty()) break;
            page.forEach(item -> ids.add(item.id()));
            beforeId = page.get(page.size() - 1).id();
        }

        assertEquals(total, ids.size());
        Set<Long> unique = new HashSet<>(ids);
        assertEquals(ids.size(), unique.size());
        for (int i = 1; i < ids.size(); i++) {
            assertTrue(ids.get(i - 1) > ids.get(i), "최신순(id 내림차순)이어야 한다");
        }
    }

    @Test
    void 페이지_크기는_1에서_200_사이만_허용한다() {
        assertEquals(1, controller.getTransfers(1, null).size());
        assertTrue(controller.getTransfers(200, null).size() <= 200);
        assertThrows(IllegalArgumentException.class, () -> controller.getTransfers(0, null));
        assertThrows(IllegalArgumentException.class, () -> controller.getTransfers(201, null));
    }
}
