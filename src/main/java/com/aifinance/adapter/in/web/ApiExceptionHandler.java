package com.aifinance.adapter.in.web;

import com.aifinance.domain.account.InsufficientBalanceException;
import com.aifinance.domain.idempotency.IdempotencyKeyMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<Map<String, String>> insufficient(InsufficientBalanceException e) {
        return ResponseEntity.status(422).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyMismatchException.class)
    public ResponseEntity<Map<String, String>> idempotencyMismatch(IdempotencyKeyMismatchException e) {
        return ResponseEntity.status(422).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /**
     * 동시 수정 충돌(@Version). 정상 경로에서는 비관적 락 때문에 나오지 않아야 하며,
     * 나온다면 락을 우회한 갱신이 있다는 신호다 — 돈을 덮어쓰는 대신 거절하고 재시도를 안내한다.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> concurrentModification(OptimisticLockingFailureException e) {
        log.warn("동시 수정 충돌로 요청 거절: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "다른 거래와 동시에 처리되어 거절되었습니다. 다시 시도해 주세요."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
