package io.github.candyxi0.hidenest.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/** Architecture boundary tests for hide-nest modular monolith. */
class ArchitectureTest {

    private static final String EVIDENCE = "io.github.candyxi0.hidenest.evidence";
    private static final String MEMORY = "io.github.candyxi0.hidenest.memory";
    private static final String RUNTIME = "io.github.candyxi0.hidenest.runtime";
    private static final String SECURITY = "io.github.candyxi0.hidenest.security";

    /**
     * Factory: forbidden framework / infrastructure dependency rule for formal domain modules.
     * Both the main-class PASS test and the fixture REJECT test MUST call this same factory.
     */
    static ArchRule forbiddenFrameworkDependencyRule() {
        return noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "org.jooq..",
                        "jakarta.persistence..",
                        "java.sql..",
                        "com.aliyun.oss..");
    }

    private JavaClasses importMain() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.candyxi0.hidenest");
    }

    private JavaClasses importAll() {
        return new ClassFileImporter().importPackages("io.github.candyxi0.hidenest");
    }

    @Test
    void allDomainMarkersMustBeImportable() {
        JavaClasses mainClasses = importMain();

        assertMarkerPresent(mainClasses, EVIDENCE);
        assertMarkerPresent(mainClasses, MEMORY);
        assertMarkerPresent(mainClasses, RUNTIME);
        assertMarkerPresent(mainClasses, SECURITY);
    }

    private static void assertMarkerPresent(JavaClasses classes, String pkg) {
        boolean found = false;
        for (var jc : classes) {
            if (jc.getPackageName().equals(pkg)) {
                found = true;
                break;
            }
        }
        assertTrue(found, pkg + " module marker must be present in imported classes");
    }

    @Test
    void domainModulesMustNotDependOnApps() {
        JavaClasses domainClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(EVIDENCE, MEMORY, RUNTIME, SECURITY);

        ArchRule rule = noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.github.candyxi0.hidenest.api..", "io.github.candyxi0.hidenest.worker..");

        rule.check(domainClasses);
    }

    @Test
    void domainModulesMustNotDependOnEachOther() {
        JavaClasses allDomain = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(EVIDENCE, MEMORY, RUNTIME, SECURITY);

        ArchRule evidenceIsolated = noClasses()
                .that()
                .resideInAPackage(EVIDENCE)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(MEMORY + "..", RUNTIME + "..", SECURITY + "..");

        ArchRule memoryIsolated = noClasses()
                .that()
                .resideInAPackage(MEMORY)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(EVIDENCE + "..", RUNTIME + "..", SECURITY + "..");

        ArchRule runtimeIsolated = noClasses()
                .that()
                .resideInAPackage(RUNTIME)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(EVIDENCE + "..", MEMORY + "..", SECURITY + "..");

        ArchRule securityIsolated = noClasses()
                .that()
                .resideInAPackage(SECURITY)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(EVIDENCE + "..", MEMORY + "..", RUNTIME + "..");

        evidenceIsolated.check(allDomain);
        memoryIsolated.check(allDomain);
        runtimeIsolated.check(allDomain);
        securityIsolated.check(allDomain);
    }

    @Test
    void domainMustNotDependOnForbiddenFrameworks() {
        JavaClasses domainClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(EVIDENCE, MEMORY, RUNTIME, SECURITY);

        ArchRule rule = forbiddenFrameworkDependencyRule();
        assertDoesNotThrow(
                () -> rule.check(domainClasses), "Rule must PASS on main domain classes (no forbidden framework deps)");
    }

    @Test
    void apiAndWorkerMustNotDependOnEachOther() {
        JavaClasses allClasses = importMain();

        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("io.github.candyxi0.hidenest.api..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("io.github.candyxi0.hidenest.worker..");

        rule.check(allClasses);

        ArchRule reverseRule = noClasses()
                .that()
                .resideInAPackage("io.github.candyxi0.hidenest.worker..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("io.github.candyxi0.hidenest.api..");

        reverseRule.check(allClasses);
    }

    @Test
    void domainModulesMustNotDependOnContracts() {
        JavaClasses domainClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(EVIDENCE, MEMORY, RUNTIME, SECURITY);

        ArchRule rule = noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.github.candyxi0.hidenest.contracts..");

        rule.check(domainClasses);
    }

    @Test
    void negativeFixtureMustViolateForbiddenFrameworkRule() {
        JavaClasses allIncludingTests = importAll();

        ArchRule rule = forbiddenFrameworkDependencyRule();

        assertTrue(
                rule.evaluate(allIncludingTests).hasViolation(),
                "Same forbidden-framework rule must detect violation in synthetic fixture");

        assertThrows(
                AssertionError.class,
                () -> rule.check(allIncludingTests),
                "rule.check must throw when fixture depends on java.sql.Connection");
    }
}
