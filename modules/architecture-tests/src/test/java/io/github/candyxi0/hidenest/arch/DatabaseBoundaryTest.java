package io.github.candyxi0.hidenest.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.candyxi0.hidenest.application.ApplicationForbiddenDependencyFixture;
import io.github.candyxi0.hidenest.application.ApplicationForbiddenGeneratedTypeFixture;
import io.github.candyxi0.hidenest.application.ApplicationForbiddenReactiveFixture;
import io.github.candyxi0.hidenest.database.DatabaseForbiddenApplicationFixture;
import io.github.candyxi0.hidenest.evidence.EvidenceForbiddenDatabaseFixture;
import org.junit.jupiter.api.Test;

/** Executable architecture gates for the L1R application and database boundaries. */
class DatabaseBoundaryTest {

    private static final String ROOT = "io.github.candyxi0.hidenest.";
    private static final String EVIDENCE = ROOT + "evidence..";
    private static final String MEMORY = ROOT + "memory..";
    private static final String RUNTIME = ROOT + "runtime..";
    private static final String SECURITY = ROOT + "security..";
    private static final String APPLICATION = ROOT + "application..";
    private static final String DATABASE = ROOT + "database..";
    private static final String API = ROOT + "api..";
    private static final String WORKER = ROOT + "worker..";
    private static final String PAYLOAD = ROOT + "payload..";

    static ArchRule domainBoundaryRule() {
        return noClasses()
                .that()
                .resideInAnyPackage(EVIDENCE, MEMORY, RUNTIME, SECURITY)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        APPLICATION,
                        DATABASE,
                        API,
                        WORKER,
                        ROOT + "contracts..",
                        "org.springframework..",
                        "org.jooq..",
                        "org.flywaydb..",
                        "java.sql..");
    }

    static ArchRule applicationBoundaryRule() {
        return noClasses()
                .that()
                .resideInAPackage(APPLICATION)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        DATABASE,
                        API,
                        WORKER,
                        "org.springframework..",
                        "org.jooq..",
                        "org.flywaydb..",
                        "java.sql..",
                        "..generated..");
    }

    static ArchRule databaseBoundaryRule() {
        return noClasses()
                .that()
                .resideInAPackage(DATABASE)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(APPLICATION, API, WORKER);
    }

    static ArchRule generatedTypeBoundaryRule() {
        return noClasses()
                .that()
                .resideInAnyPackage(EVIDENCE, MEMORY, RUNTIME, SECURITY, APPLICATION)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(ROOT + "database.generated..");
    }

    static ArchRule payloadAdapterBoundaryRule() {
        return noClasses()
                .that()
                .resideInAPackage(PAYLOAD)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        APPLICATION,
                        DATABASE,
                        API,
                        WORKER,
                        MEMORY,
                        RUNTIME,
                        SECURITY,
                        "org.springframework..",
                        "org.jooq..",
                        "org.flywaydb..",
                        "java.sql..",
                        "..generated..");
    }

    static ArchRule projectClassesMustNotUseReactiveDatabaseApisRule() {
        return noClasses()
                .that()
                .resideInAnyPackage("io.github.candyxi0.hidenest..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("io.r2dbc..", "org.reactivestreams..");
    }

    private static JavaClasses importMainClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT.substring(0, ROOT.length() - 1));
    }

    @Test
    void formalBoundariesPassForMainClasses() {
        JavaClasses mainClasses = importMainClasses();

        assertDoesNotThrow(() -> domainBoundaryRule().check(mainClasses));
        assertDoesNotThrow(() -> applicationBoundaryRule().check(mainClasses));
        assertDoesNotThrow(() -> databaseBoundaryRule().check(mainClasses));
        assertDoesNotThrow(() -> generatedTypeBoundaryRule().check(mainClasses));
        assertDoesNotThrow(() -> payloadAdapterBoundaryRule().check(mainClasses));
        assertDoesNotThrow(
                () -> projectClassesMustNotUseReactiveDatabaseApisRule().check(mainClasses));
    }

    @Test
    void applicationFixtureIsRejectedByTheFormalApplicationRule() {
        JavaClasses fixture = new ClassFileImporter().importClasses(ApplicationForbiddenDependencyFixture.class);

        assertThrows(AssertionError.class, () -> applicationBoundaryRule().check(fixture));
    }

    @Test
    void evidenceFixtureIsRejectedByTheFormalDomainRule() {
        JavaClasses fixture = new ClassFileImporter().importClasses(EvidenceForbiddenDatabaseFixture.class);

        assertThrows(AssertionError.class, () -> domainBoundaryRule().check(fixture));
    }

    @Test
    void databaseFixtureIsRejectedByTheFormalDatabaseRule() {
        JavaClasses fixture = new ClassFileImporter().importClasses(DatabaseForbiddenApplicationFixture.class);

        assertThrows(AssertionError.class, () -> databaseBoundaryRule().check(fixture));
    }

    @Test
    void generatedTypeFixtureIsRejectedByTheFormalGeneratedTypeRule() {
        JavaClasses fixture = new ClassFileImporter().importClasses(ApplicationForbiddenGeneratedTypeFixture.class);

        assertThrows(AssertionError.class, () -> generatedTypeBoundaryRule().check(fixture));
    }

    @Test
    void reactiveFixtureIsRejectedByTheFormalProjectRule() {
        JavaClasses fixture = new ClassFileImporter().importClasses(ApplicationForbiddenReactiveFixture.class);

        assertThrows(AssertionError.class, () -> projectClassesMustNotUseReactiveDatabaseApisRule()
                .check(fixture));
    }
}
