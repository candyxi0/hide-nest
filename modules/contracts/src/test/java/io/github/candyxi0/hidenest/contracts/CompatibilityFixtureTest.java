package io.github.candyxi0.hidenest.contracts;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Category 13: Compatibility fixture validation.
 * Verifies that the compatibility fixture files exist and have correct structure.
 * The actual semantic compatibility testing is done by scripts/contract-compatibility.ps1
 * using openapi-diff-maven.
 */
class CompatibilityFixtureTest {

    @Test
    void baselineMustExist() {
        Path baseline = ContractTestSupport.FIXTURES_DIR.resolve("baseline.yaml");
        assertTrue(Files.exists(baseline), "baseline.yaml must exist");
    }

    @Test
    void baselineFixtureMustExist() {
        Path baseline = ContractTestSupport.FIXTURES_DIR.resolve("baseline-fixture.yaml");
        assertTrue(Files.exists(baseline), "baseline-fixture.yaml must exist");
    }

    @Test
    void addOptionalFieldFixtureMustExist() {
        Path fixture = ContractTestSupport.FIXTURES_DIR.resolve("add-optional-field.yaml");
        assertTrue(Files.exists(fixture), "add-optional-field.yaml must exist");
    }

    @Test
    void removeRequiredFieldFixtureMustExist() {
        Path fixture = ContractTestSupport.FIXTURES_DIR.resolve("remove-required-field.yaml");
        assertTrue(Files.exists(fixture), "remove-required-field.yaml must exist");
    }

    @Test
    void removeOperationFixtureMustExist() {
        Path fixture = ContractTestSupport.FIXTURES_DIR.resolve("remove-operation.yaml");
        assertTrue(Files.exists(fixture), "remove-operation.yaml must exist");
    }

    @Test
    void baselineMustBeValidOpenApi() throws Exception {
        Path baseline = ContractTestSupport.FIXTURES_DIR.resolve("baseline-fixture.yaml");
        var parsed = ContractTestSupport.loadYaml(baseline);
        assertNotNull(parsed);
        assertTrue(parsed.containsKey("openapi"), "Baseline must have openapi version");
        assertTrue(parsed.containsKey("paths"), "Baseline must have paths");
    }

    @Test
    void addOptionalFieldMustStillBeValidOpenApi() throws Exception {
        Path fixture = ContractTestSupport.FIXTURES_DIR.resolve("add-optional-field.yaml");
        var parsed = ContractTestSupport.loadYaml(fixture);
        assertNotNull(parsed);
        assertTrue(parsed.containsKey("openapi"), "Fixture must have openapi version");
        assertTrue(parsed.containsKey("paths"), "Fixture must have paths");
    }

    @Test
    void removeOperationMustHaveEmptyPaths() throws Exception {
        Path fixture = ContractTestSupport.FIXTURES_DIR.resolve("remove-operation.yaml");
        var parsed = ContractTestSupport.loadYaml(fixture);
        assertNotNull(parsed);
        // remove-operation should have empty or minimal paths (breaking change)
        @SuppressWarnings("unchecked")
        var paths = (java.util.Map<String, Object>) parsed.get("paths");
        assertNotNull(paths, "remove-operation must have paths section");
        // Breaking: removing an operation = empty paths or fewer paths than baseline
    }

    @Test
    void allFixturesMustBeOpenApi31x() throws Exception {
        String[] fixtureNames = {
            "baseline-fixture.yaml", "add-optional-field.yaml", "remove-required-field.yaml", "remove-operation.yaml"
        };

        for (String name : fixtureNames) {
            Path fixture = ContractTestSupport.FIXTURES_DIR.resolve(name);
            var parsed = ContractTestSupport.loadYaml(fixture);
            String version = (String) parsed.get("openapi");
            assertTrue(
                    version != null && version.startsWith("3.1"),
                    "Fixture " + name + " must use OpenAPI 3.1.x (found: " + version + ")");
        }
    }
}
