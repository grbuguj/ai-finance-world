package com.aifinance.application.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 시뮬레이션의 가상 시계. 가상 시각 = 시작 시각(app.simulation.start-date) + 틱 수 × 분/틱(app.simulation.minutes-per-tick).
 *
 * 실제 시계(서버의 wall-clock)와는 독립적이다 — 시뮬레이션을 빠르게 돌려도, 멈춰 놔도 가상 시각은 틱 수에만 비례한다.
 * (ADR-0006 참고)
 *
 * SimulationEngine 이 틱마다 advance() 를 호출해 틱 수를 올린다. 이 클래스가 틱 수의 유일한 소유자이므로
 * LedgerPostingService 등 "거래가 언제 일어났는지" 알아야 하는 쪽은 SimulationEngine 을 몰라도 now() 만 물으면 된다
 * (SimulationEngine → 여러 UseCase → LedgerPostingService → VirtualClock 순환 의존을 피하기 위함).
 */
@Component
public class VirtualClock {

    private final LocalDateTime startDate;
    private final long minutesPerTick;
    private final AtomicLong tick = new AtomicLong(0);

    public VirtualClock(@Value("${app.simulation.start-date:2026-01-01T00:00}") String startDate,
                        @Value("${app.simulation.minutes-per-tick:10}") long minutesPerTick) {
        this.startDate = LocalDateTime.parse(startDate);
        this.minutesPerTick = Math.max(1, minutesPerTick);
    }

    /** 틱 1회 진행. 새 틱 수를 반환한다. */
    public long advance() {
        return tick.incrementAndGet();
    }

    public long currentTick() {
        return tick.get();
    }

    /** 틱 수를 0으로 되돌린다 (시뮬레이션 초기화/새 시뮬레이션 시) — 가상 시각도 시작 시각으로 복귀 */
    public void reset() {
        tick.set(0);
    }

    public LocalDateTime now() {
        return at(tick.get());
    }

    public LocalDateTime at(long tickValue) {
        return startDate.plusMinutes(tickValue * minutesPerTick);
    }

    public long minutesPerTick() {
        return minutesPerTick;
    }

    /** 시뮬레이션이 시작하는 가상 날짜 — 그 이전 날짜는 일마감 대상이 아니다 */
    public LocalDate startDate() {
        return startDate.toLocalDate();
    }
}
