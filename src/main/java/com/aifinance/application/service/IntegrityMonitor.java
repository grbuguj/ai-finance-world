package com.aifinance.application.service;

import com.aifinance.application.port.out.IntegrityMetricsPort;
import com.aifinance.application.usecase.IntegrityUseCase;
import com.aifinance.application.usecase.IntegrityUseCase.IntegrityReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 원장 무결성 검증 결과를 보관하고 주기적으로 갱신한다.
 *
 * 문제: 대시보드가 0.6초마다 /stats 를 부를 때마다 무결성 검증(원장 전체 집계)을 새로 돌려,
 *       원장이 커질수록 요청 하나가 수백 ms~수 초 걸리고 그 부하가 "열린 탭 수 × 초당 요청"으로 곱해졌다.
 * 해결: 검증은 정해진 주기에 한 번만 돌리고(배경 스케줄러 또는 TTL 만료 시 1회), 화면은 마지막 결과를 읽는다.
 *       조회 요청 수와 DB 부하가 분리된다. 결과에는 검증 시각과 소요 시간을 함께 실어 얼마나 최신인지 보이게 한다.
 */
@Service
public class IntegrityMonitor {

    private static final Logger log = LoggerFactory.getLogger(IntegrityMonitor.class);

    public record Snapshot(IntegrityReport report, LocalDateTime checkedAt, long durationMillis) {}

    private final IntegrityUseCase integrityUseCase;
    private final IntegrityMetricsPort metrics;
    private final AtomicReference<Snapshot> latest = new AtomicReference<>();

    public IntegrityMonitor(IntegrityUseCase integrityUseCase, IntegrityMetricsPort metrics) {
        this.integrityUseCase = integrityUseCase;
        this.metrics = metrics;
    }

    /** 지금 검증을 돌리고 결과를 보관한다. 동시에 여러 번 불려도 한 번만 돈다. */
    public synchronized Snapshot refresh() {
        long t0 = System.nanoTime();
        IntegrityReport report = integrityUseCase.check();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Snapshot previous = latest.getAndSet(new Snapshot(report, LocalDateTime.now(), ms));
        metrics.recordCheck(ms, report.balanced());
        boolean wasBalanced = previous == null || previous.report().balanced();
        if (!report.balanced() && wasBalanced) {
            log.error("[무결성] 원장 불일치 발생 — ∑DEBIT={} ∑CREDIT={} 분개≠2 거래={} 잔액≠원장 계좌={} 전체잔액합={}",
                    report.totalDebit(), report.totalCredit(), report.transfersWithoutTwoEntries(),
                    report.mismatchedAccountCount(), report.sumOfAllBalances());
        } else if (report.balanced() && !wasBalanced) {
            log.warn("[무결성] 원장 불일치 해소");
        }
        return latest.get();
    }

    /** 보관된 결과가 maxAge 보다 오래됐으면 갱신하고, 아니면 그대로 돌려준다 */
    public Snapshot latest(Duration maxAge) {
        Snapshot s = latest.get();
        if (s != null && Duration.between(s.checkedAt(), LocalDateTime.now()).compareTo(maxAge) <= 0) {
            return s;
        }
        synchronized (this) {
            s = latest.get();
            if (s != null && Duration.between(s.checkedAt(), LocalDateTime.now()).compareTo(maxAge) <= 0) {
                return s; // 기다리는 동안 다른 요청이 이미 갱신함 (single-flight)
            }
            return refresh();
        }
    }
}
