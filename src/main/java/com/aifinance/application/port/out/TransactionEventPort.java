package com.aifinance.application.port.out;

import com.aifinance.application.event.OutboundMessage;

/**
 * 커밋된 거래 이벤트를 외부 시스템으로 발행하는 포트 (Kafka / 인프로세스 브로커).
 *
 * 동기 계약: 브로커가 수신을 확인(ack)해야 정상 반환하고, 실패하면 예외를 던진다.
 * Outbox 릴레이는 정상 반환된 경우에만 이벤트를 PUBLISHED 로 표시한다.
 */
public interface TransactionEventPort {
    void publish(OutboundMessage message);
}
