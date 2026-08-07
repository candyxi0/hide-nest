package io.github.candyxi0.hidenest.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Inventory enum judge shared by normal and temporary mutation cases. */
class EnumCompletenessTest {

    @Test
    void sharedAndEventEnumSetsMustMatchInventoryExactly() {
        assertDoesNotThrow(() -> EnumJudge.assertMatches(
                ContractTestSupport.OPENAPI_SPEC,
                ContractTestSupport.PROBLEM_DETAIL_SCHEMA,
                ContractTestSupport.EVENT_SCHEMA,
                ContractTestSupport.INVENTORY));
    }

    @Test
    void formalEnumJudgeRejectsFailureCodeAndEventTypeMutations() throws Exception {
        Path tempRoot = Files.createTempDirectory("hdm003-r2-enum-judge-");
        try {
            Path badOpenApi = tempRoot.resolve("bad-failure-code.yaml");
            Files.writeString(
                    badOpenApi,
                    ContractTestSupport.readString(ContractTestSupport.OPENAPI_SPEC)
                            .replaceFirst("REQUEST_SCHEMA_INVALID", "REQUEST_SCHEMA_MUTATED"),
                    StandardCharsets.UTF_8);
            AssertionError failureCodeError = assertThrows(
                    AssertionError.class,
                    () -> EnumJudge.assertMatches(
                            badOpenApi,
                            ContractTestSupport.PROBLEM_DETAIL_SCHEMA,
                            ContractTestSupport.EVENT_SCHEMA,
                            ContractTestSupport.INVENTORY));
            assertTrue(failureCodeError.getMessage().contains("enum mismatch"));

            Path badEventSchema = tempRoot.resolve("bad-event-type.json");
            Files.writeString(
                    badEventSchema,
                    ContractTestSupport.readString(ContractTestSupport.EVENT_SCHEMA)
                            .replaceFirst("closeout.received.v1", "closeout.received.mutated"),
                    StandardCharsets.UTF_8);
            AssertionError eventTypeError = assertThrows(
                    AssertionError.class,
                    () -> EnumJudge.assertMatches(
                            ContractTestSupport.OPENAPI_SPEC,
                            ContractTestSupport.PROBLEM_DETAIL_SCHEMA,
                            badEventSchema,
                            ContractTestSupport.INVENTORY));
            assertTrue(eventTypeError.getMessage().contains("EventType enum mismatch"));
        } finally {
            deleteTree(tempRoot);
        }
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

    static final class EnumJudge {
        private EnumJudge() {}

        static void assertMatches(Path openApiPath, Path problemDetailPath, Path eventSchemaPath, Path inventoryPath)
                throws IOException {
            JsonNode spec = ContractTestSupport.JSON.valueToTree(ContractTestSupport.loadYaml(openApiPath));
            JsonNode inventory = ContractTestSupport.loadJson(inventoryPath);
            JsonNode schemas = required(spec.at("/components/schemas"), "OpenAPI component schemas");
            for (JsonNode expected : inventory.at("/schemas/sharedEnums")) {
                String name = expected.path("name").asText();
                JsonNode actual = required(schemas.get(name), "OpenAPI enum " + name);
                require(expected.path("values").equals(actual.path("enum")), "enum mismatch: " + name);
            }

            JsonNode problemFailureCodes =
                    ContractTestSupport.loadJson(problemDetailPath).at("/$defs/FailureCode/enum");
            require(problemFailureCodes.equals(schemas.at("/FailureCode/enum")), "ProblemDetail FailureCode mismatch");

            JsonNode expectedEventTypes = null;
            for (JsonNode schema : inventory.at("/schemas/eventSchemas")) {
                if ("EventType".equals(schema.path("name").asText())) {
                    expectedEventTypes = schema.path("values");
                    break;
                }
            }
            require(expectedEventTypes != null && expectedEventTypes.isArray(), "Inventory EventType enum missing");
            JsonNode eventTypes = ContractTestSupport.loadJson(eventSchemaPath).at("/$defs/EventType/enum");
            require(expectedEventTypes.equals(eventTypes), "EventType enum mismatch");
        }

        private static JsonNode required(JsonNode node, String label) {
            require(node != null && !node.isMissingNode(), "Missing " + label);
            return node;
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
