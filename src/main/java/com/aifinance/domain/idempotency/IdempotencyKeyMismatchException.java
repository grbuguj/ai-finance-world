package com.aifinance.domain.idempotency;

/** 같은 멱등성 키로 내용이 다른 요청이 들어온 경우 (클라이언트 버그) */
public class IdempotencyKeyMismatchException extends RuntimeException {
    public IdempotencyKeyMismatchException(String key) {
        super("이미 다른 요청에 사용된 Idempotency-Key 입니다: " + key);
    }
}
