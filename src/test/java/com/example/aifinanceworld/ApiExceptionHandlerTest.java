package com.example.aifinanceworld;

import com.aifinance.adapter.in.web.ApiExceptionHandler;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaSystemException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

/** DB 연결이 끊기는 순간의 JpaSystemException 은 503, 그 밖의 영속성 오류는 500 (db-fault 실험에서 발견). */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void 연결_오류를_감싼_JpaSystemException_은_503_이다() {
        var e = new JpaSystemException(new PersistenceException("commit failed", new SQLException("Communications link failure", "08S01")));
        var res = handler.jpaSystem(e);
        assertEquals(503, res.getStatusCode().value());
        assertNotNull(res.getHeaders().getFirst("Retry-After"));
    }

    @Test
    void 연결과_무관한_JpaSystemException_은_500_이다() {
        var e = new JpaSystemException(new PersistenceException("constraint", new SQLException("dup", "23000")));
        assertEquals(500, handler.jpaSystem(e).getStatusCode().value());
    }
}
