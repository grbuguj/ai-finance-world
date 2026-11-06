package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.application.usecase.AgentUseCase;
import com.aifinance.application.usecase.IntegrityUseCase;
import com.aifinance.application.usecase.SpendUseCase;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 HTTP 요청으로 같은 가맹점에 동시 결제한다 (k6 mixed 시나리오의 축소판).
 *
 * 서비스 메서드를 직접 부르는 테스트와 달리, HTTP 요청에는 OSIV(Open Session In View)가 적용된다.
 * 요청 범위 영속성 컨텍스트에 락 없이 읽은 가맹점 엔티티가 남아 있으면, 이후 SELECT ... FOR UPDATE 가
 * 그 낡은 엔티티를 그대로 돌려줘 잔액을 덮어쓰거나(@Version 없을 때) 버전 충돌로 실패한다(@Version 있을 때).
 */
@SpringBootTest(classes = AiFinanceWorldApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpSpendConcurrencyTest {

    @Autowired SimulationEngine engine;
    @Autowired AgentUseCase agentUseCase;
    @Autowired SpendUseCase spendUseCase;
    @Autowired IntegrityUseCase integrityUseCase;
    @Autowired Environment env;

    HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        engine.reset();
    }

    @Test
    void 같은_가맹점으로_동시_결제_400건이_모두_성공하고_원장이_맞는다() throws Exception {
        String base = "http://localhost:" + env.getProperty("local.server.port");
        List<Long> agents = agentUseCase.findAll().stream().map(a -> a.getAccount().getId()).toList();
        long merchant = spendUseCase.merchants().get(0).getId();

        AtomicInteger ok = new AtomicInteger();
        Map<String, Integer> failures = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        for (int i = 0; i < 400; i++) {
            long from = agents.get(i % agents.size());
            pool.submit(() -> {
                try {
                    HttpResponse<String> res = http.send(HttpRequest.newBuilder(
                                    URI.create(base + "/spends?from=" + from + "&merchant=" + merchant + "&amount=100"))
                            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() == 200) ok.incrementAndGet();
                    else failures.merge(res.statusCode() + " " + res.body().substring(0, Math.min(120, res.body().length())), 1, Integer::sum);
                } catch (Exception e) {
                    failures.merge(e.getClass().getSimpleName(), 1, Integer::sum);
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(2, TimeUnit.MINUTES));

        assertEquals(400, ok.get(), "실패 응답: " + failures);
        assertTrue(integrityUseCase.check().balanced(), "원장 무결성: " + integrityUseCase.check());
    }

    @Test
    void 대조군_서비스_직접_호출로_같은_가맹점_동시_결제_400건() throws Exception {
        List<Long> agents = agentUseCase.findAll().stream().map(a -> a.getAccount().getId()).toList();
        long merchant = spendUseCase.merchants().get(0).getId();
        AtomicInteger ok = new AtomicInteger();
        Map<String, Integer> failures = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        for (int i = 0; i < 400; i++) {
            long from = agents.get(i % agents.size());
            pool.submit(() -> {
                try {
                    spendUseCase.spend(from, merchant, BigDecimal.valueOf(100), "직접 호출");
                    ok.incrementAndGet();
                } catch (Exception e) {
                    Throwable root = e;
                    while (root.getCause() != null) root = root.getCause();
                    failures.merge(root.getClass().getSimpleName() + ": " + String.valueOf(root.getMessage()), 1, Integer::sum);
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(2, TimeUnit.MINUTES));
        assertEquals(400, ok.get(), "실패: " + failures);
        assertTrue(integrityUseCase.check().balanced());
    }
}
