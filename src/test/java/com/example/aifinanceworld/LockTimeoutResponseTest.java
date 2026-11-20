package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.IntegrityUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 락 대기 시간 초과: 다른 커넥션이 한 계좌 행을 FOR UPDATE 로 잡고 있는 동안 그 계좌로 송금 요청을 보낸다.
 *
 * 기대: 500 이 아니라 503 + Retry-After(재시도 안내), 부분 기록 없음(거래·원장·Outbox·잔액 모두 그대로), 무결성 유지,
 * 락이 풀린 뒤 같은 요청은 성공한다.
 * H2 에서는 LOCK_TIMEOUT=1500ms, MySQL(MySqlLockTimeoutResponseTest)에서는 innodb_lock_wait_timeout=2 로 같은 시나리오를 돌린다.
 */
@SpringBootTest(classes = AiFinanceWorldApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:locktimeout;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1500")
class LockTimeoutResponseTest {

    @Autowired SimulationEngine engine;
    @Autowired AgentUseCase agentUseCase;
    @Autowired IntegrityUseCase integrityUseCase;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired Environment env;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> postTransfer(long from, long to) throws Exception {
        String port = env.getProperty("local.server.port");
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/transfers?from=" + from + "&to=" + to + "&amount=1000"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }


    private String snapshotPortable() {
        Long t = jdbc.queryForObject("select count(*) from transfers", Long.class);
        Long l = jdbc.queryForObject("select count(*) from ledger_entries", Long.class);
        Long o = jdbc.queryForObject("select count(*) from outbox_events", Long.class);
        List<String> bal = jdbc.query("select id, balance from accounts order by id", (rs, i) -> rs.getLong(1) + "=" + rs.getBigDecimal(2).toPlainString());
        return t + "/" + l + "/" + o + "/" + bal;
    }

    @Test
    void 락_대기_초과는_503_재시도_안내로_응답하고_부분_기록이_남지_않는다() throws Exception {
        engine.reset();
        List<Long> ids = agentUseCase.findAll().stream().map(a -> a.getAccount().getId()).toList();
        long a = ids.get(0), b = ids.get(1);
        String before = snapshotPortable();

        HttpResponse<String> blocked;
        long elapsedMs;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement st = holder.createStatement()) {
                st.execute("select id from accounts where id = " + a + " for update");
            }
            long t0 = System.nanoTime();
            blocked = postTransfer(a, b);
            elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            holder.rollback();
        }

        System.out.println("[lock-timeout] status=" + blocked.statusCode() + " elapsedMs=" + elapsedMs
                + " retryAfter=" + blocked.headers().firstValue("Retry-After").orElse("-") + " body=" + blocked.body());
        assertEquals(503, blocked.statusCode(), "락 대기 초과는 500 이 아니라 503 이어야 한다: " + blocked.body());
        assertTrue(blocked.headers().firstValue("Retry-After").isPresent(), "재시도 안내(Retry-After)");
        assertTrue(blocked.body().contains("다시 시도"), blocked.body());
        assertTrue(elapsedMs >= 1000 && elapsedMs < 15_000, "락 대기 시간만큼만 기다리고 응답: " + elapsedMs + "ms");

        assertEquals(before, snapshotPortable(), "부분 기록 없음: 거래·원장·Outbox·잔액이 요청 전과 같아야 한다");
        assertTrue(integrityUseCase.check().balanced());

        HttpResponse<String> retry = postTransfer(a, b);
        assertEquals(200, retry.statusCode(), "락이 풀린 뒤 재시도는 성공: " + retry.body());
        assertTrue(integrityUseCase.check().balanced());
    }
}
