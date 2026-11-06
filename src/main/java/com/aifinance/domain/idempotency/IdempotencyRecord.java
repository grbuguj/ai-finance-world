package com.aifinance.domain.idempotency;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 멱등성 키 기록. 키(PK) 유니크 제약이 "같은 요청은 한 번만 처리"를 DB 수준에서 보장한다.
 * 거래와 같은 트랜잭션에서 저장되므로, 거래가 롤백되면 키도 함께 사라져 재시도가 가능하다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key", length = 100)
    private String key;

    // 요청 내용(보내는 계좌, 받는 계좌, 금액, 유형) 해시 — 같은 키로 다른 요청이 오면 거절
    @Column(length = 64, nullable = false)
    private String requestHash;

    private Long transferId;

    private LocalDateTime createdAt;

    public IdempotencyRecord(String key, String requestHash) {
        this.key = key;
        this.requestHash = requestHash;
        this.createdAt = LocalDateTime.now();
    }

    public void complete(Long transferId) {
        this.transferId = transferId;
    }

    public boolean matches(String requestHash) {
        return this.requestHash.equals(requestHash);
    }
}
