package com.aifinance.application.event;

/**
 * 브로커로 나가는 메시지 한 건.
 *
 * @param eventId 추적용 고유 id (헤더)
 * @param key     파티션 키 — 같은 키는 같은 파티션 → 순서 보장
 * @param payload 직렬화된 이벤트 본문(JSON)
 */
public record OutboundMessage(String eventId, String key, String payload) {
}
