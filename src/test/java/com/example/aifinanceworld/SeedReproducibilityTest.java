package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 시드 고정 재현 (app.simulation.seed / engine.setSeed).
 *
 * 재현의 비교 기준: 거래 id(자동 증가)와 실제 시각(createdAt)은 비교하지 않는다.
 * 대신 "거래 발생 순서대로의 (유형, 보내는 이, 받는 이, 금액, 사유)" 시퀀스와 계좌별 최종 잔액이 같아야 한다.
 * 가상 시각(occurred_at)은 틱 수에서 결정되므로 시퀀스에 포함해도 같지만, 의미가 중복이라 뺐다.
 */
@SpringBootTest(classes = AiFinanceWorldApplication.class, properties = "app.simulation.agent-count=10")
class SeedReproducibilityTest {

    private static final int TICKS = 160; // 가상 하루(144틱)를 넘겨 일마감 대사 트리거까지 지나간다

    @Autowired SimulationEngine engine;
    @Autowired JdbcTemplate jdbc;

    record Run(List<String> sequence, List<String> balances) {}

    private Run run(Long seed, int ticks) {
        engine.reset();
        engine.setSeed(seed);
        for (int i = 0; i < ticks; i++) {
            engine.runTick();
        }
        List<String> seq = jdbc.query("""
                select t.tx_type, s.name, r.name, t.amount, t.description, t.occurred_at
                from transfers t join accounts s on s.id = t.sender_id join accounts r on r.id = t.receiver_id
                order by t.id""",
                (rs, i) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|"
                        + rs.getBigDecimal(4).toPlainString() + "|" + rs.getString(5) + "|" + rs.getTimestamp(6));
        List<String> bal = jdbc.query("select name, balance from accounts order by name",
                (rs, i) -> rs.getString(1) + "=" + rs.getBigDecimal(2).toPlainString());
        return new Run(seq, bal);
    }

    @Test
    void 같은_시드_같은_에이전트_수면_거래_시퀀스와_최종_잔액이_완전히_같다() {
        Run a = run(42L, TICKS);
        Run b = run(42L, TICKS);

        System.out.println("[seed-repro] seed=42 ticks=" + TICKS + " transfers=" + a.sequence().size()
                + " accounts=" + a.balances().size());
        assertTrue(a.sequence().size() > 500, "의미 있는 규모의 거래가 만들어져야 한다: " + a.sequence().size());
        assertTrue(a.sequence().stream().anyMatch(s -> s.startsWith("INVEST_RETURN|")), "투자 정산(수익률 샘플링)도 포함돼야 한다");
        assertTrue(a.sequence().stream().anyMatch(s -> s.startsWith("REFUND|")), "환불도 포함돼야 한다");
        assertEquals(a.sequence(), b.sequence());
        assertEquals(a.balances(), b.balances());
    }

    @Test
    void 시드가_다르면_거래_시퀀스와_잔액이_달라진다() {
        Run a = run(42L, TICKS);
        Run b = run(43L, TICKS);
        assertNotEquals(a.sequence(), b.sequence());
        assertNotEquals(a.balances(), b.balances());
    }

    @Test
    void 시드가_없으면_매번_다르게_돈다() {
        Run a = run(null, 60);
        Run b = run(null, 60);
        assertNotEquals(a.sequence(), b.sequence());
    }
}
