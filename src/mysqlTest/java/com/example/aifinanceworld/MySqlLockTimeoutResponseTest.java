package com.example.aifinanceworld;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * LockTimeoutResponseTest 를 실제 MySQL(InnoDB)에서 같은 시나리오로 실행한다.
 * 모든 커넥션에 innodb_lock_wait_timeout=2 를 적용해, 행 락 대기 초과(MySQL 오류 1205)가 2초 안에 나게 한다.
 */
@Import(MySqlContainerConfig.class)
@ActiveProfiles("mysqltest")
@TestPropertySource(properties = "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout=2")
class MySqlLockTimeoutResponseTest extends LockTimeoutResponseTest {
}
