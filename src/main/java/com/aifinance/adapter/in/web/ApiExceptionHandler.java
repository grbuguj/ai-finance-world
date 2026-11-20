package com.aifinance.adapter.in.web;

import com.aifinance.domain.account.InsufficientBalanceException;
import com.aifinance.domain.idempotency.IdempotencyKeyMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
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
        log.warn("동시 수정 충돌로 요청 거절 (락을 우회한 갱신 경로 점검 필요): {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "다른 거래와 동시에 처리되어 거절되었습니다. 다시 시도해 주세요."));
    }

    /**
     * 락 대기 시간 초과·데드락 (InnoDB innodb_lock_wait_timeout, DeadlockLoser 포함). 트랜잭션 전체가 롤백되어
     * 거래·원장·Outbox·잔액 어디에도 흔적이 없으므로, 500 이 아니라 "잠시 후 다시 시도"를 뜻하는 503 + Retry-After 로 응답한다.
     */
    @ExceptionHandler({PessimisticLockingFailureException.class, QueryTimeoutException.class})
    public ResponseEntity<Map<String, String>> lockTimeout(RuntimeException e) {
        log.warn("락 대기 초과/데드락으로 요청 거절 (롤백됨, 재시도 가능): {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1")
                .body(Map.of("error", "같은 계좌를 처리 중인 다른 거래 때문에 지연되어 거절되었습니다. 기록은 남지 않았으니 잠시 후 다시 시도해 주세요."));
    }

    /** DB 연결 불가 (커넥션 풀 타임아웃 등). Hikari connection-timeout 이 짧아 오래 매달리지 않고 빠르게 실패한다. */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<Map<String, String>> databaseUnavailable(RuntimeException e) {
        log.warn("DB 연결 불가로 요청 거절: {}", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "2")
                .body(Map.of("error", "데이터베이스에 일시적으로 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
