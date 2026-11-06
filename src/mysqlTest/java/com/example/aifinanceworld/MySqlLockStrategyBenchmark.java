package com.example.aifinanceworld;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** 락 전략 비교 실험을 실제 MySQL 8(InnoDB 행 락·갭 락·데드락 감지)에서 실행한다. 논문 수치는 이 결과를 쓴다. */
@Import(MySqlContainerConfig.class)
@ActiveProfiles("mysqltest")
class MySqlLockStrategyBenchmark extends LockStrategyBenchmark {

    @Override
    int opsPerRun() {
        return 2_000;
    }

    @Override
    boolean auditSupported() {
        return true;
    }

    @Override
    String dbLabel() {
        return "mysql";
    }
}
