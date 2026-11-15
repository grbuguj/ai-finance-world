package com.example.aifinanceworld;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** OutboxReliabilityTest 를 실제 MySQL(InnoDB) 에서 동일하게 실행한다. */
@Import(MySqlContainerConfig.class)
@ActiveProfiles("mysqltest")
class MySqlOutboxReliabilityTest extends OutboxReliabilityTest {
}
