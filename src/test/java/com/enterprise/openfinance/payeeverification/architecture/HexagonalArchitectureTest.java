package com.enterprise.openfinance.payeeverification.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The four hexagonal rules of the FinTechBankX service guardrails (ADR-028).
 * They run in ./gradlew test and therefore in check.
 */
class HexagonalArchitectureTest {

    private static final String ROOT = "com.enterprise.openfinance.payeeverification";
    private static final String DOMAIN = ROOT + ".domain..";
    private static final String APPLICATION = ROOT + ".application..";
    private static final String INFRASTRUCTURE = ROOT + ".infrastructure..";
    private static final String PORT_IN = ROOT + ".domain.port.in..";
    private static final String PORT_OUT = ROOT + ".domain.port.out..";

    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(ROOT);

    @Test
    void domainDependsOnNoOuterLayerOrFramework() {
        noClasses().that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(
                APPLICATION, INFRASTRUCTURE,
                "org.springframework..", "org.springframework.data..", "org.springframework.kafka..",
                "jakarta.persistence..", "jakarta.validation..", "org.hibernate..",
                "org.apache.kafka..", "com.mongodb..", "com.fasterxml..", "lombok..")
            .check(CLASSES);
    }

    @Test
    void applicationDependsOnNoInfrastructure() {
        noClasses().that().resideInAPackage(APPLICATION)
            .should().dependOnClassesThat().resideInAnyPackage(INFRASTRUCTURE)
            .check(CLASSES);
    }

    @Test
    void controllersAndListenersCallInboundPortsNotApplicationClasses() {
        noClasses().that().areAnnotatedWith(RestController.class).or(holdKafkaListeners())
            .should().dependOnClassesThat().resideInAPackage(APPLICATION)
            .check(CLASSES);
        classes().that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().resideInAPackage(PORT_IN)
            .check(CLASSES);
    }

    @Test
    void outboundPortImplementationsLiveInInfrastructure() {
        classes().that().implement(resideIn(PORT_OUT)).and().areNotInterfaces()
            .should().resideInAPackage(INFRASTRUCTURE)
            .check(CLASSES);
    }

    private static DescribedPredicate<JavaClass> holdKafkaListeners() {
        return DescribedPredicate.describe("hold @KafkaListener methods", javaClass ->
            javaClass.getMethods().stream().anyMatch(method -> method.isAnnotatedWith(KafkaListener.class)));
    }

    private static DescribedPredicate<JavaClass> resideIn(String packageIdentifier) {
        return JavaClass.Predicates.resideInAPackage(packageIdentifier);
    }
}
