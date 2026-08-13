package io.github.candyxi0.hidenest.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Recursive closure judge shared by the authoritative spec and temporary mutations. */
class SchemaStructureTest {

    @Test
    void componentSchemaSetMustMatchInventory() throws Exception {
        JsonNode spec = ContractTestSupport.JSON.valueToTree(ContractTestSupport.loadOpenApiSpec());
        JsonNode actual = spec.at("/components/schemas");
        JsonNode inventory = ContractTestSupport.loadInventory();
        Set<String> expected = new HashSet<>();
        for (JsonNode schema : inventory.at("/schemas/apiSchemas"))
            expected.add(schema.path("name").asText());
        for (JsonNode schema : inventory.at("/schemas/sharedEnums"))
            expected.add(schema.path("name").asText());
        Set<String> found = new HashSet<>();
        found.addAll(actual.propertyNames());
        assertEquals(expected, found);
    }

    @Test
    void everySchemaObjectMustBeClosedRecursively() {
        assertDoesNotThrow(() -> ClosureJudge.assertClosed(ContractTestSupport.OPENAPI_SPEC));
    }

    @Test
    void formalClosureJudgeRejectsOpenAdditionalProperties() throws Exception {
        Path tempRoot = Files.createTempDirectory("hdm003-r2-closure-judge-");
        try {
            Path mutated = tempRoot.resolve("missing-inline-closure.yaml");
            String source = ContractTestSupport.readString(ContractTestSupport.OPENAPI_SPEC);
            String mutatedSource = source.replace(
                    "        confirmationSourceUnitId:\n          type: string\n          format: uuid\n      additionalProperties: false",
                    "        confirmationSourceUnitId:\n          type: string\n          format: uuid");
            assertTrue(!source.equals(mutatedSource), "open additionalProperties mutation must change the temporary spec");
            Files.writeString(mutated, mutatedSource, StandardCharsets.UTF_8);
            AssertionError failure = assertThrows(AssertionError.class, () -> ClosureJudge.assertClosed(mutated));
            assertTrue(failure.getMessage().contains("additionalProperties"));
        } finally {
            deleteTree(tempRoot);
        }
    }

    @Test
    void securitySchemesMustRemainSeparate() throws Exception {
        JsonNode spec = ContractTestSupport.JSON.valueToTree(ContractTestSupport.loadOpenApiSpec());
        JsonNode schemes = spec.at("/components/securitySchemes");
        assertEquals(Set.of("bearerSession", "deviceCredential", "actionCapability"), propertyNames(schemes));
    }

    private static Set<String> propertyNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
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

    static final class ClosureJudge {
        private ClosureJudge() {}

        static void assertClosed(Path specPath) throws IOException {
            JsonNode spec = ContractTestSupport.JSON.valueToTree(ContractTestSupport.loadYaml(specPath));
            JsonNode schemas = spec.at("/components/schemas");
            require(!schemas.isMissingNode(), "OpenAPI component schemas missing");
            schemas.properties().forEach(entry -> assertClosed(entry.getKey(), entry.getValue()));
        }

        private static void assertClosed(String location, JsonNode node) {
            if (node.isObject()) {
                JsonNode type = node.get("type");
                if (type != null && type.isTextual() && "object".equals(type.asText()) && !node.has("$ref")) {
                    require(node.has("additionalProperties"), location + " lacks additionalProperties");
                    require(!node.path("additionalProperties").asBoolean(true), location + " accepts unknown fields");
                }
                node.properties().forEach(entry -> assertClosed(location + "." + entry.getKey(), entry.getValue()));
            } else if (node.isArray()) {
                for (int index = 0; index < node.size(); index++)
                    assertClosed(location + "[" + index + "]", node.get(index));
            }
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
