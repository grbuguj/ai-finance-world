package com.aifinance.adapter.out.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 기동 시(스키마 생성 뒤) 원장 불변성 트리거를 설치한다. app.ledger.guard.enabled=true 일 때만. */
@Component
@Order(0)
@ConditionalOnProperty(name = "app.ledger.guard.enabled", havingValue = "true")
public class LedgerGuardInstaller implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LedgerGuardInstaller.class);
    private final LedgerGuard guard;

    public LedgerGuardInstaller(LedgerGuard guard) {
        this.guard = guard;
    }

    @Override
    public void run(ApplicationArguments args) {
        guard.install();
        log.info("[원장 가드] transfers / ledger_entries 의 UPDATE·DELETE 차단 트리거 설치 완료");
    }
}
