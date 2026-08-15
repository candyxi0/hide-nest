package io.github.candyxi0.hidenest.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeQueryAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqVectorStoreAdapter;
import io.github.candyxi0.hidenest.embedding.HttpEmbeddingProviderAdapter;
import io.github.candyxi0.hidenest.evidence.EvidencePortForbiddenGeneratedTypeFixture;
import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import io.github.candyxi0.hidenest.memory.MemoryPortForbiddenGeneratedTypeFixture;
import io.github.candyxi0.hidenest.memory.port.EmbeddingProviderPort;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.memory.port.MemoryVectorStorePort;
import io.github.candyxi0.hidenest.runtime.RuntimePortForbiddenGeneratedTypeFixture;
import io.github.candyxi0.hidenest.runtime.port.RuntimeQueryPort;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Port contract and body text leak boundary tests for HDM-006 Slice C1 R1. */
class PortBoundaryTest {

    // ── Port signature boundaries ───────────────────────────────────────

    @Test
    void portSignaturesMustNotExposeGeneratedTypes() {
        JavaClasses portClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(
                        "io.github.candyxi0.hidenest.evidence.port",
                        "io.github.candyxi0.hidenest.memory.port",
                        "io.github.candyxi0.hidenest.runtime.port");

        ArchRule rule = noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.jooq..",
                        "java.sql..",
                        "org.springframework..",
                        "io.github.candyxi0.hidenest.database.generated..");

        assertDoesNotThrow(
                () -> rule.check(portClasses),
                "Port interfaces must not expose jOOQ, JDBC, Spring, or generated types");
    }

    @Test
    void portSignaturesMustNotExposeInfrastructureTypes() {
        JavaClasses portClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(
                        "io.github.candyxi0.hidenest.evidence.port",
                        "io.github.candyxi0.hidenest.memory.port",
                        "io.github.candyxi0.hidenest.runtime.port");

        var allClasses = portClasses.iterator();
        while (allClasses.hasNext()) {
            var jc = allClasses.next();
            for (var method : jc.getMethods()) {
                var returnType = method.getRawReturnType();
                var returnPkg = returnType.getPackageName();
                assertTrue(
                        returnPkg.isEmpty()
                                || !returnPkg.startsWith("org.jooq")
                                        && !returnPkg.startsWith("java.sql")
                                        && !returnPkg.startsWith("org.springframework"),
                        jc.getName() + "#" + method.getName() + " return type must not be: " + returnPkg);

                for (var param : method.getRawParameterTypes()) {
                    var paramPkg = param.getPackageName();
                    assertTrue(
                            paramPkg.isEmpty()
                                    || !paramPkg.startsWith("org.jooq")
                                            && !paramPkg.startsWith("java.sql")
                                            && !paramPkg.startsWith("org.springframework"),
                            jc.getName() + "#" + method.getName() + " param type must not be: " + paramPkg);
                }
            }
        }
    }

    @Test
    void negativePortFixtureMustViolateGeneratedTypeRule() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(EvidencePortForbiddenGeneratedTypeFixture.class);

        ArchRule rule = noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.jooq..",
                        "java.sql..",
                        "org.springframework..",
                        "io.github.candyxi0.hidenest.database.generated..");

        assertThrows(AssertionError.class, () -> rule.check(fixture),
                "Fixture importing generated type must be rejected by port signature rule");
    }

    // ── Body text leak scan ────────────────────────────────────────────

    private static final Set<String> FORBIDDEN_BODY_FIELD_NAMES = Set.of(
            "bodyText", "prompt", "answer", "chainOfThought", "rawPayload");

    /**
     * Returns the repository root by walking up from the current working directory
     * looking for the top-level pom.xml. Fails if not found.
     */
    private static Path resolveRepoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml")) && Files.exists(dir.resolve(".git"))) {
                return dir;
            }
            Path parent = dir.getParent();
            if (parent == null || parent.equals(dir)) {
                break;
            }
            dir = parent;
        }
        fail("Cannot resolve repository root from " + Paths.get("").toAbsolutePath());
        return null;
    }

    /**
     * Scan a set of paths relative to repoRoot for forbidden body text field names.
     * Returns the list of scanned .java files (absolute paths).
     */
    private static List<Path> scanBodyTextLeaks(Path repoRoot, List<String> relativeRoots) throws IOException {
        List<Path> scannedFiles = new ArrayList<>();
        for (String rel : relativeRoots) {
            Path root = repoRoot.resolve(rel);
            assertTrue(Files.isDirectory(root), "Scan root must exist: " + root);
            try (var files = Files.walk(root)) {
                List<Path> javaFiles = files
                        .filter(p -> p.toString().endsWith(".java"))
                        .sorted()
                        .toList();
                assertTrue(javaFiles.size() > 0, "Scan root must contain >=1 .java file: " + root);
                for (Path javaFile : javaFiles) {
                    String content = Files.readString(javaFile, StandardCharsets.UTF_8);
                    for (String forbiddenName : FORBIDDEN_BODY_FIELD_NAMES) {
                        if (content.contains(forbiddenName)) {
                            fail("Forbidden body text field name '" + forbiddenName
                                    + "' found in " + repoRoot.relativize(javaFile));
                        }
                    }
                    scannedFiles.add(javaFile);
                }
            }
        }
        return scannedFiles;
    }

    private static String sha256Hex(List<Path> files) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Path f : files) {
                md.update(f.toString().getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void domainAndPortSourcesMustNotContainBodyTextFieldNames() throws IOException {
        Path repoRoot = resolveRepoRoot();

        // Scan roots: all three port packages, new evidence domain, ActorRef only, new runtime domain
        List<String> scanRoots = List.of(
                "modules/evidence/src/main/java/io/github/candyxi0/hidenest/evidence/port",
                "modules/memory/src/main/java/io/github/candyxi0/hidenest/memory/port",
                "modules/runtime/src/main/java/io/github/candyxi0/hidenest/runtime/port",
                "modules/evidence/src/main/java/io/github/candyxi0/hidenest/evidence/domain",
                "modules/runtime/src/main/java/io/github/candyxi0/hidenest/runtime/domain");

        List<Path> scannedFiles = scanBodyTextLeaks(repoRoot, scanRoots);

        // Additionally scan only ActorRef.java in memory domain (skip MemoryRevision.bodyText which is legitimate)
        Path actorRefFile = repoRoot.resolve(
                "modules/memory/src/main/java/io/github/candyxi0/hidenest/memory/domain/ActorRef.java");
        assertTrue(Files.isRegularFile(actorRefFile), "ActorRef.java must exist for scan");
        String actorRefContent = Files.readString(actorRefFile, StandardCharsets.UTF_8);
        for (String forbiddenName : FORBIDDEN_BODY_FIELD_NAMES) {
            if (actorRefContent.contains(forbiddenName)) {
                fail("Forbidden body text field name '" + forbiddenName + "' found in ActorRef.java");
            }
        }
        scannedFiles.add(actorRefFile);

        scannedFiles.sort(Path::compareTo);
        assertTrue(scannedFiles.size() > 0, "Must scan at least one file");

        System.out.println("[body-text-scan] files=" + scannedFiles.size()
                + " hash=" + sha256Hex(scannedFiles));
    }

    @Test
    void bodyTextScanCanaryMustBeRejected() throws IOException {
        Path repoRoot = resolveRepoRoot();

        String canaryPath = "modules/architecture-tests/src/test/java/io/github/candyxi0/hidenest/arch/canary";

        try {
            scanBodyTextLeaks(repoRoot, List.of(canaryPath));
            fail("Scan must reject canary file containing forbidden field name 'bodyText'");
        } catch (AssertionError e) {
            assertTrue(
                    e.getMessage().contains("bodyText"),
                    "Rejection message must name the forbidden field; got: " + e.getMessage());
        }
    }

    // ── Adapter override completeness ────────────────────────────────────

    @Test
    void evidenceAdapterOverridesMustExactlyMatchPortAbstractMethods() {
        java.util.Set<String> portAbstract = abstractMethodKeys(EvidenceReferencePort.class);

        java.util.Set<String> adapterMethodKeys = allPublicMethodKeys(JooqEvidenceReferenceAdapter.class);

        java.util.Set<String> missing = new java.util.HashSet<>(portAbstract);
        missing.removeAll(adapterMethodKeys);
        java.util.Set<String> extra = new java.util.HashSet<>(adapterMethodKeys);
        extra.removeAll(portAbstract);
        assertTrue(portAbstract.equals(adapterMethodKeys),
                "JooqEvidenceReferenceAdapter override mismatch. Port-only(missing from adapter): "
                        + missing + " Adapter-only(extra beyond port): " + extra);
    }

    @Test
    void memoryAdapterOverridesMustExactlyMatchPortAbstractMethods() {
        java.util.Set<String> portAbstract = abstractMethodKeys(MemoryGovernancePort.class);

        java.util.Set<String> adapterMethodKeys = allPublicMethodKeys(JooqMemoryGovernanceAdapter.class);

        java.util.Set<String> missing = new java.util.HashSet<>(portAbstract);
        missing.removeAll(adapterMethodKeys);
        java.util.Set<String> extra = new java.util.HashSet<>(adapterMethodKeys);
        extra.removeAll(portAbstract);
        assertTrue(portAbstract.equals(adapterMethodKeys),
                "JooqMemoryGovernanceAdapter override mismatch. Port-only(missing from adapter): "
                        + missing + " Adapter-only(extra beyond port): " + extra);
    }

    @Test
    void runtimeTransactionAdapterOverridesMustExactlyMatchPortAbstractMethods() {
        java.util.Set<String> portAbstract = abstractMethodKeys(RuntimeTransactionPort.class);

        java.util.Set<String> adapterMethodKeys = allPublicMethodKeys(JooqRuntimeTransactionAdapter.class);

        java.util.Set<String> missing = new java.util.HashSet<>(portAbstract);
        missing.removeAll(adapterMethodKeys);
        java.util.Set<String> extra = new java.util.HashSet<>(adapterMethodKeys);
        extra.removeAll(portAbstract);
        assertTrue(portAbstract.equals(adapterMethodKeys),
                "JooqRuntimeTransactionAdapter override mismatch. Port-only(missing from adapter): "
                        + missing + " Adapter-only(extra beyond port): " + extra);
    }

    @Test
    void runtimeQueryAdapterOverridesMustExactlyMatchPortAbstractMethods() {
        java.util.Set<String> portAbstract = abstractMethodKeys(RuntimeQueryPort.class);

        java.util.Set<String> adapterMethodKeys = allPublicMethodKeys(JooqRuntimeQueryAdapter.class);

        java.util.Set<String> missing = new java.util.HashSet<>(portAbstract);
        missing.removeAll(adapterMethodKeys);
        java.util.Set<String> extra = new java.util.HashSet<>(adapterMethodKeys);
        extra.removeAll(portAbstract);
        assertTrue(portAbstract.equals(adapterMethodKeys),
                "JooqRuntimeQueryAdapter override mismatch. Port-only(missing from adapter): "
                        + missing + " Adapter-only(extra beyond port): " + extra);
    }

    @Test
    void vectorStoreAdapterOverridesMustExactlyMatchPortAbstractMethods() {
        java.util.Set<String> portAbstract = abstractMethodKeys(MemoryVectorStorePort.class);

        java.util.Set<String> adapterMethodKeys = allPublicMethodKeys(JooqVectorStoreAdapter.class);

        java.util.Set<String> missing = new java.util.HashSet<>(portAbstract);
        missing.removeAll(adapterMethodKeys);
        java.util.Set<String> extra = new java.util.HashSet<>(adapterMethodKeys);
        extra.removeAll(portAbstract);
        assertTrue(portAbstract.equals(adapterMethodKeys),
                "JooqVectorStoreAdapter override mismatch. Port-only(missing from adapter): "
                        + missing + " Adapter-only(extra beyond port): " + extra);
    }

    @Test
    void embeddingAdapterOverridesMustExactlyMatchPortAbstractMethods() {
        java.util.Set<String> portAbstract = abstractMethodKeys(EmbeddingProviderPort.class);

        java.util.Set<String> adapterMethodKeys = allPublicMethodKeys(HttpEmbeddingProviderAdapter.class);

        java.util.Set<String> missing = new java.util.HashSet<>(portAbstract);
        missing.removeAll(adapterMethodKeys);
        java.util.Set<String> extra = new java.util.HashSet<>(adapterMethodKeys);
        extra.removeAll(portAbstract);
        assertTrue(portAbstract.equals(adapterMethodKeys),
                "HttpEmbeddingProviderAdapter override mismatch. Port-only(missing from adapter): "
                        + missing + " Adapter-only(extra beyond port): " + extra);
    }

    /**
     * Collects method keys for all abstract methods declared in the given class/interface.
     */
    private static java.util.Set<String> abstractMethodKeys(Class<?> clazz) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Method m : clazz.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                keys.add(methodKey(m));
            }
        }
        return keys;
    }

    /**
     * Collects method keys for all public methods declared in the given class.
     */
    private static java.util.Set<String> allPublicMethodKeys(Class<?> clazz) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Method m : clazz.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                keys.add(methodKey(m));
            }
        }
        return keys;
    }

    @Test
    void negativeMemoryPortFixtureMustViolateGeneratedTypeRule() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(MemoryPortForbiddenGeneratedTypeFixture.class);

        ArchRule rule = noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.jooq..",
                        "java.sql..",
                        "org.springframework..",
                        "io.github.candyxi0.hidenest.database.generated..");

        assertThrows(AssertionError.class, () -> rule.check(fixture),
                "Memory fixture importing generated type must be rejected by port signature rule");
    }

    @Test
    void negativeRuntimePortFixtureMustViolateGeneratedTypeRule() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(RuntimePortForbiddenGeneratedTypeFixture.class);

        ArchRule rule = noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "org.jooq..",
                        "java.sql..",
                        "org.springframework..",
                        "io.github.candyxi0.hidenest.database.generated..");

        assertThrows(AssertionError.class, () -> rule.check(fixture),
                "Runtime fixture importing generated type must be rejected by port signature rule");
    }

    /**
     * Returns a canonical key for a method: {@code name(paramType1,paramType2,...)}.
     */
    private static String methodKey(Method m) {
        var sb = new StringBuilder();
        sb.append(m.getName()).append('(');
        var params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(params[i].getSimpleName());
        }
        sb.append(')');
        return sb.toString();
    }
}
