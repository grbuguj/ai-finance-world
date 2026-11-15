package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import com.aifinance.application.port.out.AccountPort;
import com.aifinance.application.usecase.TransferUseCase;
import com.aifinance.domain.account.Account;
import com.aifinance.domain.transfer.Transfer;
import com.aifinance.domain.transfer.TransferStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 동시 송금 검증. 매 회차마다 새 계좌 2개를 만들고, 그 계좌의 원장/송금 이력만 집계한다.
 *
 * 측정 구간: 첫 submit 직전 ~ 모든 Future 완료(또는 제한시간 도달) 직후.
 * 워커 스레드 생성·submit 루프·완료 대기를 포함하고, 계좌 생성·사후 검증은 제외한다.
 * 100건은 32개 스레드 풀에 제출된 것이며 "정확히 동시에 실행"된 것이 아니다.
 */
@SpringBootTest(classes = AiFinanceWorldApplication.class)
public class ConcurrentTransferTest {

    private static final int WORKER_THREADS = 32;
    private static final int RUNS = 10;
    private static final long TIMEOUT_SECONDS = 30;
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final Path OUT_DIR = Path.of("concurrency-results");

    private static final List<RunResult> ALL_RESULTS = Collections.synchronizedList(new ArrayList<>());

    @Autowired private TransferUseCase transferUseCase;
    @Autowired private AccountPort accountPort;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    private record Outcome(String direction, boolean success, String errorType, String errorMessage) {}

    private static final class RunResult {
        String db, scenario;
        int run, submitted, succeeded, failed, unfinished;
        int aToBSucceeded, bToASucceeded;
        boolean timedOut;
        long elapsedMs;
        BigDecimal balanceA, balanceB;
        BigDecimal debitSum = BigDecimal.ZERO, creditSum = BigDecimal.ZERO;
        long debitCount, creditCount, transferCount, transferSuccessCount;
        boolean verified;
        String failReasons = "", errorSummary = "";
    }

    @Test
    void 단일방향_동시송금_100건_10회() throws Exception {
        runScenario("단일방향", new BigDecimal("10000000"), BigDecimal.ZERO, 100, 0);
    }

    @Test
    void 양방향_동시송금_100건_10회() throws Exception {
        runScenario("양방향", new BigDecimal("5000000"), new BigDecimal("5000000"), 50, 50);
    }

    private void runScenario(String scenario, BigDecimal initA, BigDecimal initB, int aToB, int bToA)
            throws Exception {
        List<String> failedRuns = new ArrayList<>();
        for (int run = 1; run <= RUNS; run++) {
            RunResult r = executeRun(scenario, run, initA, initB, aToB, bToA);
            ALL_RESULTS.add(r);
            System.out.printf("[%s #%d] submitted=%d ok=%d fail=%d unfinished=%d timeout=%s elapsed=%dms A=%s B=%s "
                            + "debit=%s/%d credit=%s/%d transfers=%d verified=%s %s%n",
                    scenario, run, r.submitted, r.succeeded, r.failed, r.unfinished, r.timedOut, r.elapsedMs,
                    r.balanceA, r.balanceB, r.debitSum, r.debitCount, r.creditSum, r.creditCount,
                    r.transferCount, r.verified, r.verified ? "" : "FAIL_REASONS=" + r.failReasons);
            if (!r.errorSummary.isEmpty() && !r.errorSummary.equals("{}")) {
                System.out.println("  errors: " + r.errorSummary);
            }
            if (!r.verified) failedRuns.add("#" + run + " " + r.failReasons);
        }
        // 모든 회차를 기록한 뒤에 실패를 반영한다(실패 회차도 CSV에 남는다)
        assertTrue(failedRuns.isEmpty(), scenario + " 검증 실패 회차: " + failedRuns);
    }

    private RunResult executeRun(String scenario, int run, BigDecimal initA, BigDecimal initB,
                                 int aToB, int bToA) throws Exception {
        // ---- 측정 제외: 계좌 생성 ----
        Account a = accountPort.save(Account.builder().balance(initA).build());
        Account b = accountPort.save(Account.builder().balance(initB).build());
        Long idA = a.getId(), idB = b.getId();
        int submitted = aToB + bToA;

        RunResult r = new RunResult();
        r.db = dbName();
        r.scenario = scenario;
        r.run = run;
        r.submitted = submitted;

        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < Math.max(aToB, bToA); i++) {
            if (i < aToB) tasks.add(task("A->B", idA, idB));
            if (i < bToA) tasks.add(task("B->A", idB, idA));
        }

        List<Outcome> outcomes = new ArrayList<>();
        Map<String, Integer> errorCounts = new LinkedHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(WORKER_THREADS);
        List<Future<Outcome>> futures = new ArrayList<>();
        try {
            // ---- 측정 구간 시작 ----
            long t0 = System.nanoTime();
            long deadline = t0 + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            for (Callable<Outcome> t : tasks) futures.add(executor.submit(t));
            for (Future<Outcome> f : futures) {
                long remaining = Math.max(0, deadline - System.nanoTime());
                try {
                    outcomes.add(f.get(remaining, TimeUnit.NANOSECONDS));
                } catch (TimeoutException e) {
                    r.timedOut = true;
                    break;
                } catch (ExecutionException e) {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    outcomes.add(new Outcome("?", false, c.getClass().getSimpleName(), sanitize(c.getMessage())));
                }
            }
            long t1 = System.nanoTime();
            // ---- 측정 구간 끝 ----
            r.elapsedMs = TimeUnit.NANOSECONDS.toMillis(t1 - t0);
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                System.out.println("경고: 워커 스레드가 10초 내 종료되지 않음");
            }
        }

        // 결과를 수집하지 못한 요청은 모두 미완료로 센다
        r.unfinished = submitted - outcomes.size();
        for (Outcome o : outcomes) {
            if (o.success()) {
                r.succeeded++;
                if (o.direction().equals("A->B")) r.aToBSucceeded++;
                if (o.direction().equals("B->A")) r.bToASucceeded++;
            } else {
                r.failed++;
                errorCounts.merge(o.errorType() + ": " + o.errorMessage(), 1, Integer::sum);
            }
        }
        r.errorSummary = errorCounts.toString();

        // ---- 사후 검증(측정 제외) ----
        List<String> problems = new ArrayList<>();
        verify(r, idA, idB, initA, initB, aToB, bToA, problems);
        r.verified = problems.isEmpty();
        r.failReasons = String.join(" | ", problems);
        return r;
    }

    private Callable<Outcome> task(String direction, Long from, Long to) {
        return () -> {
            try {
                Transfer t = transferUseCase.transfer(from, to, AMOUNT, "동시성 테스트 " + direction);
                boolean ok = t != null && t.getId() != null && t.getType() == TransferStatus.SUCCESS;
                return new Outcome(direction, ok, ok ? null : "UnexpectedResult", ok ? null : "null/non-SUCCESS transfer");
            } catch (Throwable e) {
                return new Outcome(direction, false, e.getClass().getSimpleName(), sanitize(e.getMessage()));
            }
        };
    }

    private void verify(RunResult r, Long idA, Long idB, BigDecimal initA, BigDecimal initB,
                        int aToB, int bToA, List<String> problems) {
        // 1) 요청 결과
        if (r.timedOut) problems.add("timeout");
        if (r.unfinished != 0) problems.add("unfinished=" + r.unfinished);
        if (r.succeeded != r.submitted) problems.add("succeeded=" + r.succeeded + "/" + r.submitted);
        if (r.failed != 0) problems.add("failed=" + r.failed);
        if (r.aToBSucceeded != aToB) problems.add("A->B success=" + r.aToBSucceeded + " expected " + aToB);
        if (r.bToASucceeded != bToA) problems.add("B->A success=" + r.bToASucceeded + " expected " + bToA);

        // 2) 잔액
        BigDecimal expA = initA.subtract(AMOUNT.multiply(BigDecimal.valueOf(aToB)))
                .add(AMOUNT.multiply(BigDecimal.valueOf(bToA)));
        BigDecimal expB = initB.add(AMOUNT.multiply(BigDecimal.valueOf(aToB)))
                .subtract(AMOUNT.multiply(BigDecimal.valueOf(bToA)));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        Account[] res = tx.execute(s -> new Account[]{
                accountPort.findByIdWithLock(idA).orElseThrow(),
                accountPort.findByIdWithLock(idB).orElseThrow()});
        r.balanceA = res[0].getBalance();
        r.balanceB = res[1].getBalance();
        if (expA.compareTo(r.balanceA) != 0) problems.add("A balance " + r.balanceA + " expected " + expA);
        if (expB.compareTo(r.balanceB) != 0) problems.add("B balance " + r.balanceB + " expected " + expB);
        if (initA.add(initB).compareTo(r.balanceA.add(r.balanceB)) != 0) problems.add("balance sum not preserved");

        // 3) 원장: 테스트 계좌만 (account_id, type)별 건수/합계
        Map<String, Long> countsByKey = new LinkedHashMap<>();
        Map<String, BigDecimal> sumsByKey = new LinkedHashMap<>();
        jdbc.query("select account_id, type, count(*) c, coalesce(sum(amount),0) s from ledger_entries "
                        + "where account_id in (?, ?) group by account_id, type", rs -> {
            String key = (rs.getLong("account_id") == idA ? "A" : "B") + ":" + rs.getString("type");
            countsByKey.put(key, rs.getLong("c"));
            sumsByKey.put(key, rs.getBigDecimal("s"));
            if ("DEBIT".equals(rs.getString("type"))) {
                r.debitCount += rs.getLong("c");
                r.debitSum = r.debitSum.add(rs.getBigDecimal("s"));
            } else {
                r.creditCount += rs.getLong("c");
                r.creditSum = r.creditSum.add(rs.getBigDecimal("s"));
            }
        }, idA, idB);
        // A 는 aToB 건 DEBIT, bToA 건 CREDIT / B 는 반대
        expectLedger(problems, countsByKey, sumsByKey, "A:DEBIT", aToB);
        expectLedger(problems, countsByKey, sumsByKey, "A:CREDIT", bToA);
        expectLedger(problems, countsByKey, sumsByKey, "B:DEBIT", bToA);
        expectLedger(problems, countsByKey, sumsByKey, "B:CREDIT", aToB);

        // 4) 송금 이력: 테스트 계좌가 관여한 (sender, receiver, type)별 건수
        long[] cnt = new long[3]; // [A->B SUCCESS, B->A SUCCESS, 그 외 전체]
        jdbc.query("select sender_id, receiver_id, type, count(*) c from transfers "
                + "where sender_id in (?, ?) or receiver_id in (?, ?) group by sender_id, receiver_id, type", rs -> {
            long c = rs.getLong("c");
            r.transferCount += c;
            boolean success = "SUCCESS".equals(rs.getString("type"));
            if (success) r.transferSuccessCount += c;
            long s = rs.getLong("sender_id"), rc = rs.getLong("receiver_id");
            if (success && s == idA && rc == idB) cnt[0] += c;
            else if (success && s == idB && rc == idA) cnt[1] += c;
            else cnt[2] += c;
        }, idA, idB, idA, idB);
        if (cnt[0] != aToB) problems.add("transfers A->B=" + cnt[0] + " expected " + aToB);
        if (cnt[1] != bToA) problems.add("transfers B->A=" + cnt[1] + " expected " + bToA);
        if (cnt[2] != 0) problems.add("unexpected transfers=" + cnt[2]);

        // 5) 전체 합계 (DEBIT == CREDIT == 제출 수 x 1,000)
        BigDecimal expTotal = AMOUNT.multiply(BigDecimal.valueOf(aToB + bToA));
        if (r.debitSum.compareTo(expTotal) != 0) problems.add("DEBIT sum " + r.debitSum + " expected " + expTotal);
        if (r.creditSum.compareTo(expTotal) != 0) problems.add("CREDIT sum " + r.creditSum + " expected " + expTotal);
        if (r.debitCount != aToB + bToA) problems.add("DEBIT count " + r.debitCount);
        if (r.creditCount != aToB + bToA) problems.add("CREDIT count " + r.creditCount);
    }

    private void expectLedger(List<String> problems, Map<String, Long> counts, Map<String, BigDecimal> sums,
                              String key, int expectedCount) {
        long c = counts.getOrDefault(key, 0L);
        BigDecimal s = sums.getOrDefault(key, BigDecimal.ZERO);
        BigDecimal expS = AMOUNT.multiply(BigDecimal.valueOf(expectedCount));
        if (c != expectedCount) problems.add("ledger " + key + " count " + c + " expected " + expectedCount);
        if (s.compareTo(expS) != 0) problems.add("ledger " + key + " sum " + s + " expected " + expS);
    }

    private String dbName() {
        try (Connection c = dataSource.getConnection()) {
            return c.getMetaData().getDatabaseProductName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 예외 메시지는 유형 식별용으로만 쓰고, 길이를 제한하며 줄바꿈·쉼표를 제거한다. */
    private static String sanitize(String m) {
        if (m == null) return "";
        String s = m.replaceAll("[\\r\\n,]+", " ");
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    @AfterAll
    static void writeCsv() throws IOException {
        Files.createDirectories(OUT_DIR);
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("DB,시나리오,회차,작업스레드수,제출수,성공수,실패수,미완료수,타임아웃여부,전체소요시간_ms,")
          .append("A최종잔액,B최종잔액,A->B성공수,B->A성공수,DEBIT합계,DEBIT건수,CREDIT합계,CREDIT건수,")
          .append("송금이력건수,송금이력_SUCCESS건수,검증통과,실패사유,예외요약\n");
        synchronized (ALL_RESULTS) {
            ALL_RESULTS.stream()
                    .sorted((x, y) -> x.scenario.equals(y.scenario) ? Integer.compare(x.run, y.run)
                            : x.scenario.compareTo(y.scenario))
                    .forEach(r -> sb.append(String.join(",",
                            q(r.db), q(r.scenario), String.valueOf(r.run), String.valueOf(WORKER_THREADS),
                            String.valueOf(r.submitted), String.valueOf(r.succeeded), String.valueOf(r.failed),
                            String.valueOf(r.unfinished), String.valueOf(r.timedOut), String.valueOf(r.elapsedMs),
                            String.valueOf(r.balanceA), String.valueOf(r.balanceB),
                            String.valueOf(r.aToBSucceeded), String.valueOf(r.bToASucceeded),
                            r.debitSum.toPlainString(), String.valueOf(r.debitCount),
                            r.creditSum.toPlainString(), String.valueOf(r.creditCount),
                            String.valueOf(r.transferCount), String.valueOf(r.transferSuccessCount),
                            r.verified ? "PASS" : "FAIL", q(r.failReasons), q(r.errorSummary))).append('\n'));
        }
        String db = ALL_RESULTS.isEmpty() ? "unknown"
                : ALL_RESULTS.get(0).db.toLowerCase().replaceAll("[^a-z0-9]", "");
        Files.writeString(OUT_DIR.resolve("raw-results-" + db + ".csv"), sb.toString(), StandardCharsets.UTF_8);
    }

    private static String q(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "'")) + "\"";
    }
}
