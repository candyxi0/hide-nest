package io.github.candyxi0.hidenest.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Uses one structured judge for the production operation matrix and its mutations. */
class OperationMatrixTest {

    @Test
    void operationGraphMustMatchInventoryExactly() {
        assertDoesNotThrow(
                () -> OperationJudge.assertMatches(ContractTestSupport.OPENAPI_SPEC, ContractTestSupport.INVENTORY));
    }

    @Test
    void formalOperationJudgeRejectsOperationIdAndProblemMediaMutations() throws Exception {
        Path tempRoot = Files.createTempDirectory("hdm003-r2-operation-judge-");
        try {
            String source = ContractTestSupport.readString(ContractTestSupport.OPENAPI_SPEC);
            Path badOperationId = tempRoot.resolve("bad-operation-id.yaml");
            Files.writeString(
                    badOperationId,
                    source.replaceFirst("operationId: createSession", "operationId: createSessionMutation"),
                    StandardCharsets.UTF_8);
            AssertionError operationIdFailure = assertThrows(
                    AssertionError.class,
                    () -> OperationJudge.assertMatches(badOperationId, ContractTestSupport.INVENTORY));
            assertTrue(operationIdFailure.getMessage().contains("operationId"));

            Path badProblemMedia = tempRoot.resolve("bad-problem-media.yaml");
            String badProblemMediaSource = source.replaceFirst(
                    "(?s)(ProblemUnauthorized:\\R.*?content:\\R\\s*)application/problem\\+json:",
                    "$1application/json:");
            assertTrue(!source.equals(badProblemMediaSource), "Problem media mutation must change the temporary spec");
            Files.writeString(badProblemMedia, badProblemMediaSource, StandardCharsets.UTF_8);
            AssertionError problemMediaFailure = assertThrows(
                    AssertionError.class,
                    () -> OperationJudge.assertMatches(badProblemMedia, ContractTestSupport.INVENTORY));
            assertTrue(problemMediaFailure.getMessage().contains("Problem media type"));
        } finally {
            deleteTree(tempRoot);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Cannot delete temporary path " + path, exception);
                }
            });
        }
    }

    /** Formal judge intentionally accepts paths so normal and mutation cases share all semantics. */
    static final class OperationJudge {
        private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
        private static final Set<String> APPROVED_SECURITY =
                Set.of("bearerSession", "deviceCredential", "actionCapability");
        private static final String IDEMPOTENCY_REF = "#/components/parameters/IdempotencyKey";
        private static final String PROBLEM_REF = "#/components/schemas/ProblemDetail";

        private OperationJudge() {}

        static void assertMatches(Path specPath, Path inventoryPath) throws IOException {
            JsonNode spec = ContractTestSupport.JSON.valueToTree(ContractTestSupport.loadYaml(specPath));
            JsonNode inventory = ContractTestSupport.loadJson(inventoryPath);
            JsonNode idempotencyKey = requiredPointer(spec, "/components/parameters/IdempotencyKey", "IdempotencyKey");
            assertIdempotencyComponent(idempotencyKey);

            Map<String, JsonNode> expected = expectedOperations(inventory);
            Map<String, JsonNode> actual = actualOperations(spec);
            require(expected.keySet().equals(actual.keySet()), "method + effective path set must match Inventory");
            require(actual.size() == 30, "expected exactly 30 operations, found " + actual.size());

            int idempotent = 0;
            int nonIdempotent = 0;
            int requiredCapability = 0;
            int conditionalCapability = 0;
            int noCapability = 0;
            int noStore = 0;

            for (Map.Entry<String, JsonNode> entry : expected.entrySet()) {
                String key = entry.getKey();
                JsonNode expectedOperation = entry.getValue();
                JsonNode actualOperation = actual.get(key);
                require(
                        expectedOperation
                                .path("operationId")
                                .asText()
                                .equals(actualOperation.path("operationId").asText()),
                        "operationId mismatch: " + key);
                require(
                        expectedOperation
                                .path("callerRole")
                                .asText()
                                .equals(actualOperation.path("x-caller-role").asText()),
                        "x-caller-role mismatch: " + key);

                boolean expectedIdempotent =
                        expectedOperation.path("idempotencyRequired").asBoolean();
                assertParameters(spec, expectedOperation.path("parameters"), actualOperation, expectedIdempotent, key);
                if (expectedIdempotent) idempotent++;
                else nonIdempotent++;

                String capability = expectedOperation.path("capabilityRequired").asText();
                switch (assertCapability(actualOperation, capability, key)) {
                    case "REQUIRED" -> requiredCapability++;
                    case "CONDITIONAL" -> conditionalCapability++;
                    case "NOT_REQUIRED" -> noCapability++;
                    default -> throw new AssertionError("Unknown Inventory capability: " + capability);
                }

                assertRequestBody(expectedOperation, actualOperation, key);
                assertSuccessResponses(spec, expectedOperation.path("successResponses"), actualOperation, key);
                assertProblemResponses(spec, expectedOperation.path("problemStatuses"), actualOperation, key);
                assertNoStore(spec, actualOperation, key);
                noStore++;
            }

            require(idempotent == 20 && nonIdempotent == 10, "idempotency totals must be 20/10");
            require(
                    requiredCapability == 8 && conditionalCapability == 1 && noCapability == 21,
                    "capability totals must be 8/1/21");
            require(noStore == 30, "no-store total must be 30");
        }

        private static Map<String, JsonNode> expectedOperations(JsonNode inventory) {
            Map<String, JsonNode> expected = new HashMap<>();
            for (JsonNode operation : inventory.path("apiOperations")) {
                String key = operation.path("method").asText().toUpperCase() + " "
                        + operation.path("path").asText();
                require(expected.put(key, operation) == null, "Inventory has duplicate operation: " + key);
            }
            return expected;
        }

        private static Map<String, JsonNode> actualOperations(JsonNode spec) {
            String basePath = spec.at("/servers/0/url").asText();
            require(!basePath.isBlank(), "OpenAPI server URL is required");
            Map<String, JsonNode> actual = new HashMap<>();
            spec.path("paths")
                    .properties()
                    .forEach(pathEntry -> pathEntry.getValue().properties().forEach(methodEntry -> {
                        String method = methodEntry.getKey().toUpperCase();
                        if (HTTP_METHODS.contains(method)) {
                            String key = method + " " + basePath + pathEntry.getKey();
                            require(
                                    actual.put(key, methodEntry.getValue()) == null,
                                    "OpenAPI has duplicate operation: " + key);
                        }
                    }));
            return actual;
        }

        private static void assertIdempotencyComponent(JsonNode parameter) {
            require("Idempotency-Key".equals(parameter.path("name").asText()), "IdempotencyKey name must be exact");
            require("header".equals(parameter.path("in").asText()), "IdempotencyKey must be a header");
            require(parameter.path("required").asBoolean(false), "IdempotencyKey must be required");
            require("string".equals(parameter.at("/schema/type").asText()), "IdempotencyKey must be string");
        }

        private static void assertParameters(
                JsonNode spec,
                JsonNode expectedParameters,
                JsonNode operation,
                boolean idempotencyRequired,
                String key) {
            JsonNode parameters = operation.path("parameters");
            if (parameters.isMissingNode() || parameters.isNull()) {
                require(expectedParameters.isEmpty() && !idempotencyRequired, "parameters missing: " + key);
                return;
            }
            require(parameters.isArray(), "parameters must be an array: " + key);
            int idempotencyReferences = 0;
            Map<String, JsonNode> actualRegular = new HashMap<>();
            for (JsonNode rawParameter : parameters) {
                if (IDEMPOTENCY_REF.equals(rawParameter.path("$ref").asText())) {
                    idempotencyReferences++;
                    continue;
                }
                JsonNode parameter = resolveParameter(spec, rawParameter, key);
                String parameterKey = parameter.path("name").asText() + "|"
                        + parameter.path("in").asText();
                require(
                        actualRegular.put(parameterKey, parameter) == null,
                        "duplicate regular parameter: " + key + " " + parameterKey);
            }
            require(
                    idempotencyRequired ? idempotencyReferences == 1 : idempotencyReferences == 0,
                    "IdempotencyKey component reference mismatch: " + key);
            Map<String, JsonNode> expectedRegular = new HashMap<>();
            for (JsonNode expectedParameter : expectedParameters) {
                String parameterKey = expectedParameter.path("name").asText() + "|"
                        + expectedParameter.path("in").asText();
                require(
                        expectedRegular.put(parameterKey, expectedParameter) == null,
                        "Inventory duplicate parameter: " + key);
            }
            require(expectedRegular.keySet().equals(actualRegular.keySet()), "regular parameter set mismatch: " + key);
            for (Map.Entry<String, JsonNode> expected : expectedRegular.entrySet()) {
                assertParameterSchema(
                        expected.getValue(), actualRegular.get(expected.getKey()), key + " " + expected.getKey());
            }
        }

        private static JsonNode resolveParameter(JsonNode spec, JsonNode raw, String key) {
            if (!raw.has("$ref")) return raw;
            String ref = raw.path("$ref").asText();
            require(
                    ref.startsWith("#/components/parameters/"),
                    "parameter ref must be exact component pointer: " + key);
            return requiredPointer(spec, ref.substring(1), "parameter " + ref);
        }

        private static void assertParameterSchema(JsonNode expected, JsonNode actual, String label) {
            require(
                    expected.path("name").asText().equals(actual.path("name").asText()),
                    "parameter name mismatch: " + label);
            require(expected.path("in").asText().equals(actual.path("in").asText()), "parameter in mismatch: " + label);
            require(
                    expected.path("required").asBoolean(false)
                            == actual.path("required").asBoolean(false),
                    "parameter required mismatch: " + label);
            String expectedSchema = expected.path("schema").asText();
            JsonNode actualSchema = actual.path("schema");
            if ("uuid".equals(expectedSchema)) {
                require("string".equals(actualSchema.path("type").asText()), "uuid parameter type mismatch: " + label);
                require(
                        "uuid".equals(actualSchema.path("format").asText()),
                        "uuid parameter format mismatch: " + label);
                require(!actualSchema.has("$ref"), "uuid parameter must not use a different ref: " + label);
            } else if ("string".equals(expectedSchema) || "integer".equals(expectedSchema)) {
                require(expectedSchema.equals(actualSchema.path("type").asText()), "parameter type mismatch: " + label);
                require(!actualSchema.has("$ref"), "primitive parameter must not use a ref: " + label);
            } else {
                require(
                        ("#/components/schemas/" + expectedSchema)
                                .equals(actualSchema.path("$ref").asText()),
                        "parameter schema ref mismatch: " + label);
            }
        }

        private static String assertCapability(JsonNode operation, String expectedCapability, String key) {
            JsonNode security = operation.path("security");
            require(security.isArray() && !security.isEmpty(), "security must be a non-empty array: " + key);
            boolean anyCapability = false;
            boolean everyCapability = true;
            for (JsonNode requirement : security) {
                require(requirement.isObject() && requirement.size() > 0, "security requirement is invalid: " + key);
                requirement
                        .propertyNames()
                        .forEach(scheme -> require(
                                APPROVED_SECURITY.contains(scheme),
                                "unapproved security scheme " + scheme + ": " + key));
                boolean hasCapability = requirement.has("actionCapability");
                anyCapability |= hasCapability;
                everyCapability &= hasCapability;
            }
            switch (expectedCapability) {
                case "REQUIRED" -> require(everyCapability, "required capability missing: " + key);
                case "CONDITIONAL" ->
                    require(anyCapability && !everyCapability, "conditional capability mismatch: " + key);
                case "NOT_REQUIRED" -> require(!anyCapability, "unexpected capability: " + key);
                default -> throw new AssertionError("Unknown Inventory capability: " + expectedCapability);
            }
            return expectedCapability;
        }

        private static void assertRequestBody(JsonNode expected, JsonNode actual, String key) {
            JsonNode expectedSchema = expected.path("requestSchema");
            JsonNode requestBody = actual.path("requestBody");
            if (expectedSchema.isMissingNode() || expectedSchema.isNull()) {
                require(requestBody.isMissingNode() || requestBody.isNull(), "unexpected request body: " + key);
                return;
            }
            require(requestBody.isObject(), "request body missing: " + key);
            assertOnlyMediaAndSchema(requestBody, "application/json", expectedSchema.asText(), "request", key);
        }

        private static void assertSuccessResponses(
                JsonNode spec, JsonNode expectedSuccesses, JsonNode operation, String key) {
            Set<String> expectedStatuses = new LinkedHashSet<>();
            Map<String, String> expectedSchemas = new HashMap<>();
            for (JsonNode expected : expectedSuccesses) {
                String status = expected.path("status").asText();
                expectedStatuses.add(status);
                expectedSchemas.put(status, expected.path("schema").asText());
            }
            Set<String> actualStatuses = new LinkedHashSet<>();
            operation.path("responses").propertyNames().forEach(status -> {
                if (status.startsWith("2")) actualStatuses.add(status);
            });
            require(expectedStatuses.equals(actualStatuses), "success status set mismatch: " + key);
            for (String status : expectedStatuses) {
                JsonNode response =
                        resolveResponse(spec, operation.path("responses").path(status), key + " " + status);
                assertOnlyMediaAndSchema(
                        response, "application/json", expectedSchemas.get(status), "success", key + " " + status);
            }
        }

        private static void assertProblemResponses(
                JsonNode spec, JsonNode expectedProblems, JsonNode operation, String key) {
            Set<String> expectedStatuses = new LinkedHashSet<>();
            for (JsonNode status : expectedProblems) expectedStatuses.add(status.asText());
            Set<String> actualStatuses = new LinkedHashSet<>();
            operation.path("responses").propertyNames().forEach(status -> {
                if (!status.startsWith("2")) actualStatuses.add(status);
            });
            require(expectedStatuses.equals(actualStatuses), "Problem status set mismatch: " + key);
            for (String status : expectedStatuses) {
                JsonNode response =
                        resolveResponse(spec, operation.path("responses").path(status), key + " " + status);
                assertOnlyMediaAndSchema(
                        response, "application/problem+json", "ProblemDetail", "Problem", key + " " + status);
            }
        }

        private static void assertOnlyMediaAndSchema(
                JsonNode responseOrBody, String mediaType, String schemaName, String kind, String label) {
            JsonNode content = responseOrBody.path("content");
            Set<String> mediaTypes = new HashSet<>();
            content.propertyNames().forEach(mediaTypes::add);
            require(mediaTypes.equals(Set.of(mediaType)), kind + " media type mismatch: " + label);
            require(
                    ("#/components/schemas/" + schemaName)
                            .equals(content.path(mediaType).at("/schema/$ref").asText()),
                    kind + " schema ref mismatch: " + label);
        }

        private static void assertNoStore(JsonNode spec, JsonNode operation, String key) {
            operation.path("responses").properties().forEach(entry -> {
                JsonNode response = resolveResponse(spec, entry.getValue(), key + " " + entry.getKey());
                require(
                        "no-store"
                                .equals(response.at("/headers/Cache-Control/schema/const")
                                        .asText()),
                        "Cache-Control no-store missing: " + key + " " + entry.getKey());
            });
        }

        private static JsonNode resolveResponse(JsonNode spec, JsonNode raw, String label) {
            if (!raw.has("$ref")) return raw;
            String ref = raw.path("$ref").asText();
            require(
                    ref.startsWith("#/components/responses/"),
                    "response ref must be exact component pointer: " + label);
            return requiredPointer(spec, ref.substring(1), "response " + ref);
        }

        private static JsonNode requiredPointer(JsonNode root, String pointer, String label) {
            JsonNode selected = root.at(pointer);
            require(!selected.isMissingNode(), "unresolvable JSON pointer for " + label + ": " + pointer);
            return selected;
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
