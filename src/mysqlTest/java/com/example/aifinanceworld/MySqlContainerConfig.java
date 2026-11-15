package com.example.aifinanceworld;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 테스트용 MySQL 8.0 컨테이너. @ServiceConnection 이 datasource url/계정을 자동으로 이 컨테이너로 연결한다.
 * 같은 설정을 쓰는 테스트 클래스끼리는 Spring 컨텍스트와 컨테이너를 재사용한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlContainerConfig {

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer("mysql:8.0")
                .withDatabaseName("ai_finance")
                .withUsername("finance")
                .withPassword("finance1234");
    }
}
