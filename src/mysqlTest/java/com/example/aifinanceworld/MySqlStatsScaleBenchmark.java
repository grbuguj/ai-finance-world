package com.example.aifinanceworld;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** /stats 비용 측정을 실제 MySQL 8 에서 */
@Import(MySqlContainerConfig.class)
@ActiveProfiles("mysqltest")
class MySqlStatsScaleBenchmark extends StatsScaleBenchmark {

    @Override
    long[] sizes() {
        return new long[]{10_000, 100_000, 400_000};
    }

    @Override
    String dbLabel() {
        return "mysql";
    }
}
