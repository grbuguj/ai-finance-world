package com.example.aifinanceworld;

import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/** 정산 수신 소비자는 일시적 오류에서 재시도를 포기하지 않는다 (db-fault 실험에서 2건이 건너뛰어져 유실됐던 문제의 회귀 방지). */
class KafkaConsumerErrorHandlingTest {

    @Test
    void 백오프는_무제한이고_간격은_5초를_넘지_않는다() throws Exception {
        Class<?> cfg = Class.forName("com.aifinance.adapter.out.messaging.SettlementConsumerErrorConfig");
        Method m = cfg.getDeclaredMethod("settlementBackOff");
        m.setAccessible(true);
        BackOffExecution exec = ((BackOff) m.invoke(null)).start();

        long last = 0;
        for (int i = 0; i < 200; i++) {
            long next = exec.nextBackOff();
            assertNotEquals(BackOffExecution.STOP, next, i + "번째 재시도에서 포기하면 레코드가 건너뛰어진다");
            assertTrue(next <= 5_000);
            last = next;
        }
        assertEquals(5_000, last);
    }
}
