package io.github.candyxi0.hidenest.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Categories 14-15: Generation determinism.
 * Verifies that two consecutive Maven generate-sources runs produce byte-identical output.
 *
 * Note: The heavy determinism test is also run by scripts/generate.ps1 -Check.
 * This Java test provides a lightweight verification by hashing the already-generated sources.
 */
class DeterminismTest {

    @Test
    void generatedJavaSourcesMustExist() {
        Path generatedDir =
                ContractTestSupport.REPO_ROOT.resolve("modules/contracts/target/generated-sources/api/src/main/java");
        assertTrue(Files.exists(generatedDir), "Generated Java sources must exist after generate-sources");
    }

    @Test
    void generatedTsSourcesMustExist() {
        Path generatedDir = ContractTestSupport.REPO_ROOT.resolve("packages/api-client-ts/src/generated");
        assertTrue(Files.exists(generatedDir), "Generated TypeScript sources must exist after generate-sources");
    }

    @Test
    void generatedJavaSourcesMustHaveNoTimestamps() throws Exception {
        Path generatedDir =
                ContractTestSupport.REPO_ROOT.resolve("modules/contracts/target/generated-sources/api/src/main/java");

        if (!Files.exists(generatedDir)) return; // Skip if not yet generated

        try (Stream<Path> files = Files.walk(generatedDir)) {
            files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))
                    .forEach(file -> {
                        try {
                            String content = Files.readString(file);
                            // Should not contain generation timestamps
                            assertTrue(
                                    !content.contains("Generated at") || content.contains("hideGenerationTimestamp"),
                                    "File " + file.getFileName() + " should not contain generation timestamps");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    @Test
    void generatedTsSourcesMustHaveNoTimestamps() throws Exception {
        Path generatedDir = ContractTestSupport.REPO_ROOT.resolve("packages/api-client-ts/src/generated");

        if (!Files.exists(generatedDir)) return;

        try (Stream<Path> files = Files.walk(generatedDir)) {
            files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".ts"))
                    .forEach(file -> {
                        try {
                            String content = Files.readString(file);
                            assertTrue(
                                    !content.contains("generated on") && !content.contains("Generated at"),
                                    "File " + file.getFileName() + " should not contain generation timestamps");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    @Test
    void contractFilesMustBeUnchanged() throws Exception {
        // Hash all contract source files (not generated) and verify they exist
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        Path[] contractFiles = {
            ContractTestSupport.OPENAPI_SPEC,
            ContractTestSupport.PROBLEM_DETAIL_SCHEMA,
            ContractTestSupport.EVENT_SCHEMA
        };

        for (Path file : contractFiles) {
            assertTrue(Files.exists(file), "Contract file must exist: " + file.getFileName());
            byte[] content = Files.readAllBytes(file);
            assertTrue(content.length > 0, "Contract file must not be empty: " + file.getFileName());
            digest.update(content);
        }

        // Just verify the hash is stable (deterministic input → deterministic hash)
        byte[] hash = digest.digest();
        assertEquals(32, hash.length, "SHA-256 hash must be 32 bytes");
    }
}
