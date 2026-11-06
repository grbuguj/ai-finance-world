package com.example.aifinanceworld;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;

/** 실제 Kafka · Redis 컨테이너 (MySqlContainerConfig 와 함께 써서 운영과 같은 인프라 구성으로 검증) */
@TestConfiguration(proxyBeanMethods = false)
public class InfraContainersConfig {

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        // native 이미지는 CI 에서 간헐적으로 기동 실패(exit 126) → JVM 이미지 사용
        return new KafkaContainer("apache/kafka:3.8.0");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    }
}
