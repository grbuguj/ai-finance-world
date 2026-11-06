package com.aifinance.adapter.in.web;

import com.aifinance.domain.account.InsufficientBalanceException;
import com.aifinance.domain.idempotency.IdempotencyKeyMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

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

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
