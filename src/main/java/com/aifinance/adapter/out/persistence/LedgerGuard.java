package com.aifinance.adapter.out.persistence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * DB 수준 원장 불변성 가드 (MySQL 전용, app.ledger.guard.enabled=true 일 때만 동작).
 *
 * transfers / ledger_entries 에 BEFORE UPDATE / BEFORE DELETE 트리거를 걸어, 애플리케이션을 우회한 직접 SQL(JDBC·콘솔)도
 * 막는다. 단 시뮬레이션 초기화(deleteAllForSimulationReset)는 DELETE 가 필요하므로, 그 트랜잭션 안에서만 세션 변수
 * @afw_allow_reset 을 켜서 트리거가 통과시키게 한다 (같은 트랜잭션 = 같은 커넥션이라 다른 세션에는 영향이 없다).
 * 꺼져 있으면(기본, H2) 아무것도 하지 않는다.
 */
@Component
public class LedgerGuard {

    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public LedgerGuard(JdbcTemplate jdbc, @Value("${app.ledger.guard.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 반드시 호출자가 연 트랜잭션 안에서 실행한다 (SimulationSetupUseCase.reset/resetAll 이 @Transactional) */
    public void allowResetDuring(Runnable deletion) {
        if (!enabled) {
            deletion.run();
            return;
        }
        jdbc.execute("SET @afw_allow_reset = 1");
        try {
            deletion.run();
        } finally {
            jdbc.execute("SET @afw_allow_reset = NULL");
        }
    }

    /** 트리거 설치 (멱등: DROP IF EXISTS 후 CREATE) */
    public void install() {
        for (String table : new String[]{"ledger_entries", "transfers"}) {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_" + table + "_no_update");
            jdbc.execute("DROP TRIGGER IF EXISTS trg_" + table + "_no_delete");
            jdbc.execute("CREATE TRIGGER trg_" + table + "_no_update BEFORE UPDATE ON " + table
                    + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '" + table + " is append-only (update blocked)'");
            jdbc.execute("CREATE TRIGGER trg_" + table + "_no_delete BEFORE DELETE ON " + table
                    + " FOR EACH ROW BEGIN IF @afw_allow_reset IS NULL THEN "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '" + table + " is append-only (delete blocked)'; END IF; END");
        }
    }
}
