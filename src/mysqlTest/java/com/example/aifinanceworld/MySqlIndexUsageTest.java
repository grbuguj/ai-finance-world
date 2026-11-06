package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 핵심 조회가 의도한 인덱스를 사용할 수 있는지 MySQL 의 EXPLAIN 으로 확인한다.
 * 데이터가 적은 테스트 DB 에서는 옵티마이저가 풀스캔을 고를 수도 있으므로, 실제 선택(key)이 아니라
 * 후보 인덱스(possible_keys)에 들어 있는지를 본다 — 인덱스 설계가 쿼리 조건과 맞는지 검증하는 목적.
 */
@Import(MySqlContainerConfig.class)
@ActiveProfiles("mysqltest")
@SpringBootTest(classes = AiFinanceWorldApplication.class)
class MySqlIndexUsageTest {

    @Autowired SimulationEngine engine;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        engine.reset();
        for (int i = 0; i < 50; i++) {
            engine.runTick(); // 거래·Outbox 데이터를 조금 만든다
        }
        jdbc.execute("analyze table transfers, outbox_events, settlement_records");
    }

    private void assertCandidateIndex(String sql, String table, String index) {
        List<Map<String, Object>> plan = jdbc.queryForList("explain " + sql);
        String possible = plan.stream()
                .filter(r -> table.equals(r.get("table")))
                .map(r -> String.valueOf(r.get("possible_keys")) + "/" + r.get("key"))
                .findFirst().orElse("(no row for " + table + ")");
        System.out.println("EXPLAIN|" + table + "|" + index + "|" + possible + "|" + plan);
        assertTrue(possible.contains(index), () -> table + " 조회가 " + index + " 를 사용할 수 없음: " + plan);
    }

    @Test
    void Outbox_릴레이_폴링은_status_next_attempt_인덱스를_쓴다() {
        assertCandidateIndex(
                "select * from outbox_events where status = 'PENDING' and next_attempt_at <= now() order by id limit 200",
                "outbox_events", "idx_outbox_status_next");
    }

    @Test
    void 대사_기간_조회는_transfers_created_at_인덱스를_쓴다() {
        assertCandidateIndex("""
                select t.id from transfers t
                left join settlement_records s on s.transfer_id = t.id
                left join outbox_events o on o.aggregate_id = t.id and o.aggregate_type = 'TRANSFER'
                where t.created_at >= now() - interval 1 day and t.created_at < now() + interval 1 day
                  and s.transfer_id is null""",
                "t", "idx_transfers_created_at");
    }

    @Test
    void 대사의_Outbox_상태_조인은_aggregate_인덱스를_쓴다() {
        assertCandidateIndex("""
                select t.id from transfers t
                left join outbox_events o on o.aggregate_id = t.id and o.aggregate_type = 'TRANSFER'
                where t.created_at >= now() - interval 1 day""",
                "o", "idx_outbox_aggregate");
    }

    @Test
    void 정산_장부_기간_조회는_occurred_at_인덱스를_쓴다() {
        assertCandidateIndex(
                "select count(*) from settlement_records where occurred_at >= now() - interval 1 day and occurred_at < now()",
                "settlement_records", "idx_settlement_occurred_at");
    }

    @Test
    void 환불_대상_조회는_sender_type_refunded_인덱스를_쓴다() {
        assertCandidateIndex(
                "select * from transfers where sender_id = 11 and tx_type = 'SPEND' and refunded = false order by id desc limit 5",
                "transfers", "idx_transfers_sender_type_refunded");
    }
}
