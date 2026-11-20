package com.example.aifinanceworld;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
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

    // ── 원장 불변성 규칙 ─────────────────────────────────────────────────────────────
    // 의도: "원장(transfers, ledger_entries)은 지우거나 고치지 않고, 정정은 반대 분개를 새로 추가한다"는 주장이
    //       코드가 바뀌어도 계속 참이어야 한다. 아래 세 규칙이 그 주장을 빌드 단계에서 강제한다.
    //       (유일한 예외: 시뮬레이션 초기화 전용 deleteAllForSimulationReset — 이름으로 명시되어 코드 리뷰에서 눈에 띈다)

    private static final String RESET_ONLY = "deleteAllForSimulationReset";

    /** 규칙 ①: 원장·거래 포트는 수정/삭제 계열 메서드를 가질 수 없다 (초기화 전용 메서드만 예외) */
    @Test
    void 원장_불변성_원장과_거래_포트는_수정_삭제_메서드를_갖지_않는다() {
        ArchCondition<JavaMethod> noModification = new ArchCondition<>("원장을 수정·삭제하는 이름을 갖지 않는다 (예외: " + RESET_ONLY + ")") {
            @Override
            public void check(JavaMethod m, ConditionEvents events) {
                String n = m.getName();
                boolean mutating = n.startsWith("delete") || n.startsWith("remove") || n.startsWith("update")
                        || n.startsWith("set") || n.startsWith("modify") || n.startsWith("mark") || n.startsWith("correct");
                if (mutating && !n.equals(RESET_ONLY)) {
                    events.add(SimpleConditionEvent.violated(m, m.getFullName()
                            + " 은(는) 원장을 수정·삭제하는 메서드다. 정정은 반대 분개를 save 로 추가해야 한다."));
                }
            }
        };
        methods().that().areDeclaredInClassesThat().haveFullyQualifiedName("com.aifinance.application.port.out.LedgerPort")
                .or().areDeclaredInClassesThat().haveFullyQualifiedName("com.aifinance.application.port.out.TransferPort")
                .should(noModification)
                .check(classes);
    }

    /** 규칙 ②: 원장·거래 엔티티는 @Immutable 이고, 값을 바꾸는 메서드(set/update/mark/describe...)를 갖지 않는다 */
    @Test
    void 원장_불변성_원장과_거래_엔티티는_Immutable_이고_변경_메서드가_없다() {
        classes().that().haveFullyQualifiedName("com.aifinance.domain.ledger.LedgerEntry")
                .or().haveFullyQualifiedName("com.aifinance.domain.transfer.Transfer")
                .should().beAnnotatedWith(org.hibernate.annotations.Immutable.class)
                .check(classes);

        noMethods().that().areDeclaredInClassesThat().haveFullyQualifiedName("com.aifinance.domain.ledger.LedgerEntry")
                .or().areDeclaredInClassesThat().haveFullyQualifiedName("com.aifinance.domain.transfer.Transfer")
                .should().haveNameMatching("(set|update|mark|describe|correct|reset|modify).*")
                .check(classes);
    }

    /** 규칙 ③: 원장·거래 JPA 리포지토리는 영속성 어댑터만 쓴다 — 유스케이스나 컨트롤러가 repository.save/delete 로 우회할 수 없다 */
    @Test
    void 원장_불변성_원장과_거래_리포지토리는_영속성_어댑터만_쓴다() {
        noClasses().that().resideOutsideOfPackages("com.aifinance.adapter.out.persistence..", "com.aifinance.domain..")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.aifinance.domain.ledger.LedgerRepository")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.aifinance.domain.transfer.TransferRepository")
                .check(classes);
    }
}
