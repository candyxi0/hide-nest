package io.github.candyxi0.hidenest.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.Error;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Canonical fixtures are judged only by the real NetworkNT schema validator. */
class FixtureValidationTest {

    @Test
    void everyManifestFixtureHasTheExpectedRealValidationOutcome() {
        assertDoesNotThrow(() -> FixtureJudge.assertManifest(ContractTestSupport.FIXTURE_MANIFEST));
    }

    @Test
    void formalFixtureJudgeRejectsUnknownFieldInjectedIntoCanonicalValidFixture() throws Exception {
        Path tempRoot = Files.createTempDirectory("hdm003-r2-fixture-judge-");
        try {
            JsonNode entry = FixtureJudge.fixtureById("openapi.create-session.response.valid");
            Path mutated = tempRoot.resolve("create-session-response-unknown-field.json");
            Files.writeString(
                    mutated,
                    injectField(
                            ContractTestSupport.readString(ContractTestSupport.REPO_ROOT.resolve(
                                    ContractTestSupport.text(entry, "fixturePath"))),
                            "hdm003R2Unknown",
                            "canary"),
                    StandardCharsets.UTF_8);
            AssertionError error = assertThrows(
                    AssertionError.class,
                    () -> FixtureJudge.assertValid(
                            ContractTestSupport.text(entry, "schemaRef"),
                            ContractTestSupport.loadJson(mutated),
                            "unknown-field mutation"));
            assertTrue(error.getMessage().contains("unexpectedly failed"));
        } finally {
            deleteTree(tempRoot);
        }
    }

    @Test
    void everyInventoryForbiddenEventFieldIsRejectedByTheSameValidator() throws Exception {
        Path tempRoot = Files.createTempDirectory("hdm003-r2-forbidden-fields-");
        try {
            JsonNode eventEntry = FixtureJudge.fixtureById("event.envelope.valid");
            String schemaRef = ContractTestSupport.text(eventEntry, "schemaRef");
            String canonicalEvent = ContractTestSupport.readString(
                    ContractTestSupport.REPO_ROOT.resolve(ContractTestSupport.text(eventEntry, "fixturePath")));
            JsonNode forbiddenFields = FixtureJudge.inventoryForbiddenFields();
            assertTrue(
                    forbiddenFields.isArray() && !forbiddenFields.isEmpty(),
                    "Inventory forbiddenFields must be present");
            for (JsonNode fieldNode : forbiddenFields) {
                String field = fieldNode.asText();
                Path mutated = tempRoot.resolve(field + ".json");
                Files.writeString(
                        mutated,
                        injectField(canonicalEvent, field, "hdm003-r2-forbidden-canary"),
                        StandardCharsets.UTF_8);
                AssertionError error = assertThrows(
                        AssertionError.class,
                        () -> FixtureJudge.assertValid(
                                schemaRef, ContractTestSupport.loadJson(mutated), "forbidden field " + field));
                assertTrue(error.getMessage().contains("unexpectedly failed"));
            }
        } finally {
            deleteTree(tempRoot);
        }
    }

    private static String injectField(String json, String field, String value) {
        String mutated = json.replaceFirst("\\R}\\s*$", ",\n  \"" + field + "\": \"" + value + "\"\n}");
        if (json.equals(mutated)) throw new IllegalArgumentException("Cannot inject field into canonical fixture");
        return mutated;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Cannot delete temporary path " + path, exception);
                }
            });
        }
    }

    /** Formal fixture judge; normal and mutation tests call these same methods. */
    static final class FixtureJudge {
        private static final Path FIXTURE_ROOT =
                ContractTestSupport.REPO_ROOT.resolve("packages/ui-contract-fixtures/src/fixtures");

        private FixtureJudge() {}

        static void assertManifest(Path manifestPath) throws IOException {
            JsonNode entries = ContractTestSupport.loadJson(manifestPath).path("fixtures");
            require(entries.isArray() && !entries.isEmpty(), "fixture manifest must contain an array");
            Set<String> ids = new HashSet<>();
            Set<String> fixturePaths = new HashSet<>();
            for (JsonNode entry : entries) {
                String id = ContractTestSupport.text(entry, "id");
                require(ids.add(id), "duplicate fixture id: " + id);
                String fixturePath = ContractTestSupport.text(entry, "fixturePath");
                require(fixturePaths.add(fixturePath), "duplicate fixture path: " + fixturePath);
                Path resolvedFixture = ContractTestSupport.REPO_ROOT.resolve(fixturePath);
                require(Files.isRegularFile(resolvedFixture), "missing fixture: " + fixturePath);
                boolean expectedValid = entry.path("expectedValid").asBoolean();
                List<Error> errors = ContractTestSupport.validateFixture(entry);
                if (expectedValid) {
                    require(errors.isEmpty(), id + " unexpectedly failed: " + errors);
                } else {
                    assertInvalid(entry, errors);
                }
            }
            assertFixtureSetHasNoOrphans(fixturePaths);
        }

        static void assertValid(String schemaRef, JsonNode instance, String label) throws IOException {
            List<Error> errors = ContractTestSupport.validateSchema(schemaRef, instance);
            require(errors.isEmpty(), label + " unexpectedly failed: " + errors);
        }

        static JsonNode fixtureById(String id) throws IOException {
            for (JsonNode entry : ContractTestSupport.loadFixtureManifest().path("fixtures")) {
                if (id.equals(entry.path("id").asText())) return entry;
            }
            throw new AssertionError("canonical fixture missing: " + id);
        }

        static JsonNode inventoryForbiddenFields() throws IOException {
            for (JsonNode schema : ContractTestSupport.loadInventory().at("/schemas/eventSchemas")) {
                if ("PinkEventEnvelope".equals(schema.path("name").asText())) return schema.path("forbiddenFields");
            }
            throw new AssertionError("Inventory PinkEventEnvelope forbiddenFields missing");
        }

        private static void assertInvalid(JsonNode entry, List<Error> errors) {
            String id = ContractTestSupport.text(entry, "id");
            require(!errors.isEmpty(), id + " unexpectedly passed schema validation");
            String expectedKeyword = ContractTestSupport.text(entry, "expectedKeyword");
            String expectedInstancePath = ContractTestSupport.text(entry, "expectedInstancePath");
            boolean matched = errors.stream()
                    .anyMatch(error -> expectedKeyword.equals(error.getKeyword())
                            && expectedInstancePath.equals(String.valueOf(error.getInstanceLocation())));
            require(
                    matched,
                    id + " did not fail at keyword/path " + expectedKeyword + " / " + expectedInstancePath + ": "
                            + errors);
        }

        private static void assertFixtureSetHasNoOrphans(Set<String> manifestPaths) throws IOException {
            Set<String> actualPaths = new HashSet<>();
            for (String kind : Set.of("valid", "invalid")) {
                Path directory = FIXTURE_ROOT.resolve(kind);
                require(Files.isDirectory(directory), "fixture directory missing: " + directory);
                try (Stream<Path> files = Files.walk(directory)) {
                    files.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(".json"))
                            .forEach(path -> actualPaths.add(ContractTestSupport.REPO_ROOT
                                    .relativize(path)
                                    .toString()
                                    .replace('\\', '/')));
                }
            }
            require(manifestPaths.equals(actualPaths), "manifest/fixture JSON path set mismatch");
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
