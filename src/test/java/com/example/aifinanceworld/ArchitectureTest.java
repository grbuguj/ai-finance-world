package com.example.aifinanceworld;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 헥사고날 아키텍처 의존 규칙을 테스트로 강제한다.
 * README 의 "비즈니스 로직이 외부 기술에 종속되지 않는다"는 주장이 코드가 바뀌어도 계속 참인지 빌드가 검사한다.
 *
 *   adapter.in ──▶ application ──▶ domain
 *   adapter.out ─▶ application.port.out (구현)
 *   external(정산 시스템) 은 은행 코어를 모른다
 */
class ArchitectureTest {

    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aifinance");
    }

    @Test
    void 도메인은_애플리케이션_어댑터_외부시스템을_모른다() {
        ArchRule rule = noClasses().that().resideInAPackage("com.aifinance.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.aifinance.application..", "com.aifinance.adapter..", "com.aifinance.external..");
        rule.check(classes);
    }

    @Test
    void 애플리케이션은_어댑터와_외부시스템을_모른다() {
        ArchRule rule = noClasses().that().resideInAPackage("com.aifinance.application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.aifinance.adapter..", "com.aifinance.external..");
        rule.check(classes);
    }

    @Test
    void 애플리케이션은_인프라_기술에_직접_의존하지_않는다() {
        ArchRule rule = noClasses().that().resideInAPackage("com.aifinance.application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework.web..",
                        "org.springframework.kafka..",
                        "org.springframework.data.redis..",
                        "org.apache.kafka..",
                        "io.micrometer..",
                        "jakarta.servlet..");
        rule.check(classes);
    }

    @Test
    void 인바운드_어댑터는_아웃바운드_어댑터를_직접_쓰지_않는다() {
        ArchRule rule = noClasses().that().resideInAPackage("com.aifinance.adapter.in..")
                .should().dependOnClassesThat().resideInAPackage("com.aifinance.adapter.out..");
        rule.check(classes);
    }

    @Test
    void 외부_정산_시스템은_은행_코어를_모른다() {
        ArchRule rule = noClasses().that().resideInAPackage("com.aifinance.external..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.aifinance.domain..", "com.aifinance.application..", "com.aifinance.adapter..");
        rule.check(classes);
    }

    @Test
    void 최상위_계층_사이에_순환_의존이_없다() {
        slices().matching("com.aifinance.(*)..").should().beFreeOfCycles().check(classes);
    }
}
