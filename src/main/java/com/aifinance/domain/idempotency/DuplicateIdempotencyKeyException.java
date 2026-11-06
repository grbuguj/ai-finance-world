package com.aifinance.domain.idempotency;

/** 같은 키가 이미 저장되었거나, 동시에 다른 요청이 같은 키로 처리 중 */
public class DuplicateIdempotencyKeyException extends RuntimeException {
    public DuplicateIdempotencyKeyException(String key, Throwable cause) {
        super("중복 Idempotency-Key: " + key, cause);
    }
}
