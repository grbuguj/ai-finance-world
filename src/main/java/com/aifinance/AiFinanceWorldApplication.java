package com.aifinance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@EnableScheduling
@EnableJpaAuditing
@SpringBootApplication
public class AiFinanceWorldApplication {

    /**
     * 영업일 기준 시간대. 거래 시각(LocalDateTime), 일마감 cron, 대사 기간 계산이 모두 이 시간대를 따라야
     * "어제"가 하나로 정의된다. 서버(컨테이너)가 UTC 로 떠도 영업일이 9시간 밀리지 않도록 JVM 기본값을 고정한다.
     */
    public static final String BUSINESS_ZONE = "Asia/Seoul";

    static {
        TimeZone.setDefault(TimeZone.getTimeZone(BUSINESS_ZONE));
    }

    public static void main(String[] args) {
        SpringApplication.run(AiFinanceWorldApplication.class, args);
    }

}
