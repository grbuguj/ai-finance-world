package com.aifinance.application.port.out;

import com.aifinance.application.event.TransactionPostedEvent;

/** 커밋된 거래 이벤트를 외부 시스템으로 발행하는 포트 (Kafka / 로그). */
public interface TransactionEventPort {
    void publish(TransactionPostedEvent event);
    long publishedCount();
}
