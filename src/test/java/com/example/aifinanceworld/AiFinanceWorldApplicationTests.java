package com.example.aifinanceworld;

import com.aifinance.AiFinanceWorldApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// 테스트 패키지(com.example)와 메인 패키지(com.aifinance)가 달라 설정 클래스를 명시한다
@SpringBootTest(classes = AiFinanceWorldApplication.class)
class AiFinanceWorldApplicationTests {

    @Test
    void contextLoads() {
    }

}
