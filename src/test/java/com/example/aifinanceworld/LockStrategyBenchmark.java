package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.adapter.in.scheduler.SimulationEngine;
import com.aifinance.application.usecase.IntegrityUseCase;
import com.aifinance.application.usecase.SimulationSetupUseCase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 락 전략 비교 실험 — "왜 비관적 락인가"를 감이 아니라 숫자로 답한다.
 *
 * 같은 계좌 테이블·같은 원장 기록(거래 1건 + 분개 2건)을 두고, 잔액 갱신 방식만 바꿔 동일 부하를 건다.
 *
 *  PESSIMISTIC   SELECT ... FOR UPDATE (계좌 ID 오름차순) → 검증 → UPDATE           ← 현재 서비스 방식
 *  OPTIMISTIC    SELECT balance, version → UPDATE ... WHERE version = ?  충돌 시 재시도(지수 백오프 + 지터)
 *  CONDITIONAL   UPDATE ... SET balance = balance - ? WHERE id = ? AND balance >= ?  (읽기 없이 원자적 차감)
 *
 * 시나리오
 *  UNIFORM        100개 계좌 사이 무작위 송금 (경합 낮음)
 *  HOTSPOT        200개 계좌 → 1개 계좌로 집중 (가맹점 결제 몰림)
 *  HOTSPOT_SHARD  200개 계좌 → 8개 서브계좌로 분산 (핫스팟 완화책)
 *  PINGPONG       2개 계좌가 서로 동시 송금 (최대 경합, 데드락 유발 패턴)
 *
 * 모든 실행 후 복식부기 무결성(잔액 = 원장, ∑DEBIT = ∑CREDIT, 총액 보존)을 확인해 "빠르지만 틀린" 전략을 걸러낸다.
 * 결과는 build/benchmark/lock-strategy-{db}.md 와 표준출력(BENCH| 접두어)으로 남긴다.
 *
 * 실행: ./gradlew benchmark (H2)   /   ./gradlew mysqlBenchmark (Testcontainers MySQL 8 — 논문 수치 기준)
 */
@Tag("benchmark")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = AiFinanceWorldApplication.class,
        properties = {"spring.datasource.hikari.maximum-pool-size=32", "app.outbox.relay.auto=false"})
class LockStrategyBenchmark {

    enum Strategy { PESSIMISTIC, OPTIMISTIC, CONDITIONAL }

    enum Scenario { UNIFORM, HOTSPOT, HOTSPOT_SHARD, PINGPONG }

    static final int THREADS = 32;
    static final int SHARDS = 8;
    static final int MAX_OPTIMISTIC_RETRIES = 30;
    static final BigDecimal AMOUNT = BigDecimal.valueOf(100);
    static final BigDecimal INITIAL = BigDecimal.valueOf(10_000_000);

    record Result(Strategy strategy, Scenario scenario, int ops, int succeeded, long failed, long retries,
                  long deadlocks, long wallMs, double tps, double p50, double p95, double p99, double max,
                  boolean integrityOk, Map<String, Long> errors) {}

    @Autowired SimulationEngine engine;
    @Autowired SimulationSetupUseCase setup;
    @Autowired IntegrityUseCase integrity;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    TransactionTemplate tx;
    final List<Result> results = Collections.synchronizedList(new ArrayList<>());

    /** 실행 규모 — MySQL 변형은 더 크게 잡는다 */
    int opsPerRun() {
        return 1_000;
    }

    String dbLabel() {
        return "h2";
    }

    @BeforeAll
    void init() {
        tx = new TransactionTemplate(txManager);
        engine.reset();
    }

    @Test
    void 락_전략별_처리량_지연_정합성_비교() throws Exception {
        for (Scenario scenario : Scenario.values()) {
            for (Strategy strategy : Strategy.values()) {
                // 예열 (JIT, 커넥션 풀, 버퍼 풀) — 기록하지 않음
                run(strategy, scenario, Math.max(100, opsPerRun() / 10), false);
                Result r = run(strategy, scenario, opsPerRun(), true);
                results.add(r);
                System.out.println(line(r));
            }
        }
        writeReport();
        results.forEach(r -> assertTrue(r.integrityOk(), "무결성 위반: " + r.strategy() + "/" + r.scenario()));
    }

    // ───────────────────────── 실행 ─────────────────────────

    Result run(Strategy strategy, Scenario scenario, int ops, boolean record) throws Exception {
        String prefix = "bench-" + strategy.name().charAt(0) + scenario.ordinal() + "-" + System.nanoTime() % 100000;
        Workload w = workload(scenario, prefix);
        BigDecimal totalBefore = totalOf(w.allIds());

        long[] latencies = new long[ops];
        AtomicLong idx = new AtomicLong();
        LongAdder failed = new LongAdder();
        LongAdder retries = new LongAdder();
        LongAdder deadlocks = new LongAdder();
        Map<String, Long> errors = new ConcurrentHashMap<>();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(ops);
        for (int i = 0; i < ops; i++) {
            final int n = i;
            pool.submit(() -> {
                try {
                    start.await();
                    long[] pair = w.next(n);
                    long t0 = System.nanoTime();
                    try {
                        retries.add(transfer(strategy, pair[0], pair[1]));
                        latencies[(int) idx.getAndIncrement()] = System.nanoTime() - t0;
                    } catch (RuntimeException e) {
                        failed.increment();
                        String key = rootCause(e);
                        if (key.toLowerCase().contains("deadlock")) deadlocks.increment();
                        errors.merge(key.length() > 80 ? key.substring(0, 80) : key, 1L, Long::sum);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        long wall0 = System.nanoTime();
        start.countDown();
        boolean finished = done.await(10, TimeUnit.MINUTES);
        long wallNanos = System.nanoTime() - wall0;
        pool.shutdownNow();
        if (!finished) throw new IllegalStateException("벤치마크 시간 초과: " + strategy + "/" + scenario);

        int ok = (int) idx.get();
        long[] lat = Arrays.copyOf(latencies, ok);
        Arrays.sort(lat);
        boolean integrityOk = integrity.check().balanced() && totalOf(w.allIds()).compareTo(totalBefore) == 0;

        if (!record) return null;
        return new Result(strategy, scenario, ops, ok, failed.sum(), retries.sum(), deadlocks.sum(),
                wallNanos / 1_000_000, ok / (wallNanos / 1e9),
                pct(lat, 50), pct(lat, 95), pct(lat, 99), lat.length == 0 ? 0 : lat[lat.length - 1] / 1e6,
                integrityOk, new TreeMap<>(errors));
    }

    /** @return 이 송금에 쓴 재시도 횟수 (낙관적 락만 0 이상) */
    long transfer(Strategy strategy, long from, long to) {
        return switch (strategy) {
            case PESSIMISTIC -> { pessimistic(from, to); yield 0; }
            case CONDITIONAL -> { conditional(from, to); yield 0; }
            case OPTIMISTIC -> optimistic(from, to);
        };
    }

    void pessimistic(long from, long to) {
        tx.executeWithoutResult(s -> {
            // 데드락 방지: 항상 작은 ID 부터 잠근다
            long first = Math.min(from, to), second = Math.max(from, to);
            BigDecimal b1 = jdbc.queryForObject("select balance from accounts where id = ? for update", BigDecimal.class, first);
            BigDecimal b2 = jdbc.queryForObject("select balance from accounts where id = ? for update", BigDecimal.class, second);
            BigDecimal fromBalance = from == first ? b1 : b2;
            if (fromBalance.compareTo(AMOUNT) < 0) throw new IllegalStateException("잔액 부족");
            jdbc.update("update accounts set balance = balance - ? where id = ?", AMOUNT, from);
            jdbc.update("update accounts set balance = balance + ? where id = ?", AMOUNT, to);
            writeLedger(from, to);
        });
    }

    long optimistic(long from, long to) {
        for (int attempt = 0; attempt <= MAX_OPTIMISTIC_RETRIES; attempt++) {
            Boolean ok = tx.execute(s -> {
                Map<String, Object> f = jdbc.queryForMap("select balance, version from accounts where id = ?", from);
                Map<String, Object> t = jdbc.queryForMap("select balance, version from accounts where id = ?", to);
                BigDecimal fb = (BigDecimal) f.get("balance");
                if (fb.compareTo(AMOUNT) < 0) throw new IllegalStateException("잔액 부족");
                // UPDATE 도 행 락을 잡으므로 ID 순서로 실행해야 데드락이 안 난다
                boolean fromFirst = from < to;
                int n1 = fromFirst ? casUpdate(from, fb.subtract(AMOUNT), f.get("version"))
                        : casUpdate(to, ((BigDecimal) t.get("balance")).add(AMOUNT), t.get("version"));
                if (n1 == 0) { s.setRollbackOnly(); return false; }
                int n2 = fromFirst ? casUpdate(to, ((BigDecimal) t.get("balance")).add(AMOUNT), t.get("version"))
                        : casUpdate(from, fb.subtract(AMOUNT), f.get("version"));
                if (n2 == 0) { s.setRollbackOnly(); return false; }
                writeLedger(from, to);
                return true;
            });
            if (Boolean.TRUE.equals(ok)) return attempt;
            backoff(attempt);
        }
        throw new IllegalStateException("낙관적 락 재시도 한도 초과");
    }

    int casUpdate(long id, BigDecimal newBalance, Object version) {
        return jdbc.update("update accounts set balance = ?, version = version + 1 where id = ? and version = ?",
                newBalance, id, ((Number) version).longValue());
    }

    void conditional(long from, long to) {
        tx.executeWithoutResult(s -> {
            // 읽지 않고 조건부로 바로 차감 — 잔액 검증과 갱신이 한 문장(원자적)
            if (from < to) {
                debit(from);
                credit(to);
            } else {
                credit(to);
                debit(from);
            }
            writeLedger(from, to);
        });
    }

    void debit(long id) {
        int n = jdbc.update("update accounts set balance = balance - ? where id = ? and balance >= ?", AMOUNT, id, AMOUNT);
        if (n == 0) throw new IllegalStateException("잔액 부족");
    }

    void credit(long id) {
        jdbc.update("update accounts set balance = balance + ? where id = ?", AMOUNT, id);
    }

    /** 세 전략 공통: 거래 1건 + DEBIT/CREDIT 분개 2건 */
    void writeLedger(long from, long to) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    insert into transfers (sender_id, receiver_id, amount, type, tx_type, description, refunded, created_at)
                    values (?, ?, ?, 'SUCCESS', 'TRANSFER', 'benchmark', false, ?)""", new String[]{"id"});
            ps.setLong(1, from);
            ps.setLong(2, to);
            ps.setBigDecimal(3, AMOUNT);
            ps.setTimestamp(4, now);
            return ps;
        }, key);
        long transferId = key.getKey().longValue();
        jdbc.update("insert into ledger_entries (account_id, transfer_id, type, amount, created_at) values (?, ?, 'DEBIT', ?, ?)",
                from, transferId, AMOUNT, now);
        jdbc.update("insert into ledger_entries (account_id, transfer_id, type, amount, created_at) values (?, ?, 'CREDIT', ?, ?)",
                to, transferId, AMOUNT, now);
    }

    static void backoff(int attempt) {
        long capMs = Math.min(1L << Math.min(attempt, 6), 50);
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(capMs + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ───────────────────────── 시나리오 ─────────────────────────

    interface Workload {
        long[] next(int n);
        List<Long> allIds();
    }

    Workload workload(Scenario scenario, String prefix) {
        return switch (scenario) {
            case UNIFORM -> {
                List<Long> ids = setup.createFundedAccounts(prefix, 100, INITIAL);
                yield new Workload() {
                    public long[] next(int n) {
                        ThreadLocalRandom r = ThreadLocalRandom.current();
                        int a = r.nextInt(ids.size()), b;
                        do { b = r.nextInt(ids.size()); } while (b == a);
                        return new long[]{ids.get(a), ids.get(b)};
                    }
                    public List<Long> allIds() { return ids; }
                };
            }
            case HOTSPOT, HOTSPOT_SHARD -> {
                List<Long> senders = setup.createFundedAccounts(prefix + "-s", 200, INITIAL);
                List<Long> receivers = setup.createFundedAccounts(prefix + "-r",
                        scenario == Scenario.HOTSPOT ? 1 : SHARDS, BigDecimal.ONE);
                List<Long> all = new ArrayList<>(senders);
                all.addAll(receivers);
                yield new Workload() {
                    public long[] next(int n) {
                        long from = senders.get(n % senders.size());
                        long to = receivers.get(ThreadLocalRandom.current().nextInt(receivers.size()));
                        return new long[]{from, to};
                    }
                    public List<Long> allIds() { return all; }
                };
            }
            case PINGPONG -> {
                List<Long> ids = setup.createFundedAccounts(prefix, 2, INITIAL);
                yield new Workload() {
                    public long[] next(int n) {
                        return n % 2 == 0 ? new long[]{ids.get(0), ids.get(1)} : new long[]{ids.get(1), ids.get(0)};
                    }
                    public List<Long> allIds() { return ids; }
                };
            }
        };
    }

    // ───────────────────────── 집계·리포트 ─────────────────────────

    BigDecimal totalOf(List<Long> ids) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < ids.size(); i += 500) {
            List<Long> chunk = ids.subList(i, Math.min(ids.size(), i + 500));
            String in = String.join(",", Collections.nCopies(chunk.size(), "?"));
            BigDecimal part = jdbc.queryForObject("select coalesce(sum(balance), 0) from accounts where id in (" + in + ")",
                    BigDecimal.class, chunk.toArray());
            sum = sum.add(part);
        }
        return sum;
    }

    static double pct(long[] sorted, int p) {
        if (sorted.length == 0) return 0;
        int i = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))] / 1e6;
    }

    static String rootCause(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c.getClass().getSimpleName() + ": " + String.valueOf(c.getMessage()).replace('\n', ' ');
    }

    static String line(Result r) {
        return String.format("BENCH|%s|%s|ops=%d|ok=%d|fail=%d|retry=%d|deadlock=%d|wall=%dms|tps=%.0f|p50=%.2f|p95=%.2f|p99=%.2f|max=%.2f|integrity=%s|errors=%s",
                r.scenario(), r.strategy(), r.ops(), r.succeeded(), r.failed(), r.retries(), r.deadlocks(), r.wallMs(),
                r.tps(), r.p50(), r.p95(), r.p99(), r.max(), r.integrityOk() ? "OK" : "BROKEN", r.errors());
    }

    void writeReport() throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# 락 전략 비교 실험 (").append(dbLabel()).append(")\n\n");
        md.append("- 실행 시각: ").append(LocalDateTime.now()).append('\n');
        md.append("- 스레드 ").append(THREADS).append(", 커넥션 풀 32, 실행당 ").append(opsPerRun()).append("건 (예열 별도)\n");
        md.append("- Java ").append(System.getProperty("java.version")).append(", CPU ")
                .append(Runtime.getRuntime().availableProcessors()).append("코어\n\n");
        md.append("| 시나리오 | 전략 | 성공 | 실패 | 재시도 | 데드락 | TPS | p50(ms) | p95(ms) | p99(ms) | max(ms) | 무결성 |\n");
        md.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|:-:|\n");
        for (Result r : results) {
            md.append(String.format("| %s | %s | %d | %d | %d | %d | %.0f | %.2f | %.2f | %.2f | %.2f | %s |%n",
                    r.scenario(), r.strategy(), r.succeeded(), r.failed(), r.retries(), r.deadlocks(), r.tps(),
                    r.p50(), r.p95(), r.p99(), r.max(), r.integrityOk() ? "✅" : "❌"));
        }
        md.append("\n## 실패 사유\n\n");
        for (Result r : results) {
            if (!r.errors().isEmpty()) {
                md.append("- ").append(r.scenario()).append(" / ").append(r.strategy()).append(": ").append(r.errors()).append('\n');
            }
        }
        Path out = Path.of("build", "benchmark", "lock-strategy-" + dbLabel() + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());
        System.out.println("BENCH-REPORT-BEGIN\n" + md + "BENCH-REPORT-END");
        assertEquals(Scenario.values().length * Strategy.values().length, results.size());
    }
}
