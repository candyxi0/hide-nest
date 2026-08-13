package io.github.candyxi0.hidenest.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Task30A Read API response contract completion — mechanical assertions.
 *
 * <p>All mutating tests write temporary files and never touch the production spec.</p>
 */
class ReadApiContractTest {

    // ── Positive shape assertions ───────────────────────────────────────────

    @Test
    void memoryDetailSchemaMustHaveExactFieldsAndRequiredSet() throws Exception {
        JsonNode schemas = ContractTestSupport.JSON.valueToTree(ContractTestSupport.loadOpenApiSpec())
                .at("/components/schemas");
        JsonNode detail = required(schemas.get("MemoryDetail"), "MemoryDetail schema");

        assertEquals(Set.of(
                "memoryId", "currentRevisionId", "revisionNo", "state", "memoryType",
                "perspectiveActorId", "bodyText", "updatedAt", "evidenceCount", "uncertaintyCode"),
                propertyNames(detail.get("properties")),
                "MemoryDetail properties set mismatch");

        assertEquals(Set.of(
                "memoryId", "currentRevisionId", "revisionNo", "state", "memoryType",
                "perspectiveActorId", "bodyText", "updatedAt", "evidenceCount"),
                requiredNames(detail),
                "MemoryDetail required set mismatch");

        assertEquals("integer", detail.at("/properties/revisionNo/type").asText());
        assertEquals(1, detail.at("/properties/revisionNo/minimum").asInt());
        assertEquals("integer", detail.at("/properties/evidenceCount/type").asText());
        assertEquals(0, detail.at("/properties/evidenceCount/minimum").asInt());
        assertEquals("string", detail.at("/properties/updatedAt/type").asText());
        assertEquals("date-time", detail.at("/properties/updatedAt/format").asText());
        assertFalse(detail.at("/additionalProperties").asBoolean(true),
                "MemoryDetail must be closed");
    }

    @Test
    void memoryEvidenceItemSchemaMustHaveExactFieldsAndAllRequired() throws Exception {
        JsonNode schemas = ContractTestSupport.JSON.valueToTree(ContractTestSupport.loadOpenApiSpec())
                .at("/components/schemas");
        JsonNode item = required(schemas.get("MemoryEvidenceItem"), "MemoryEvidenceItem schema");

        assertEquals(Set.of(
                "anchorId", "sourceUnitId", "ordinal", "actorId", "actorKind",
                "actorStableRef", "occurredAt", "bodyText"),
                propertyNames(item.get("properties")),
                "MemoryEvidenceItem properties set mismatch");

        assertEquals(Set.of(
                "anchorId", "sourceUnitId", "ordinal", "actorId", "actorKind",
                "actorStableRef", "occurredAt", "bodyText"),
                requiredNames(item),
                "MemoryEvidenceItem must have all fields required");

        assertEquals("integer", item.at("/properties/ordinal/type").asText());
        assertEquals(0, item.at("/properties/ordinal/minimum").asInt());
        assertEquals("string", item.at("/properties/occurredAt/type").asText());
        assertEquals("date-time", item.at("/properties/occurredAt/format").asText());
        assertFalse(item.at("/additionalProperties").asBoolean(true),
                "MemoryEvidenceItem must be closed");
    }

    @Test
    void memoryDetailResponseWrapperMustUseRefNotAnonymousObject() throws Exception {
        JsonNode schemas = ContractTestSupport.JSON.valueToTree(ContractTestSupport.loadOpenApiSpec())
                .at("/components/schemas");
        JsonNode response = required(schemas.get("MemoryDetailResponse"), "MemoryDetailResponse schema");

        JsonNode memory = required(response.at("/properties/memory"), "memory property");
        String ref = memory.path("$ref").asText();
        assertEquals("#/components/schemas/MemoryDetail", ref,
                "memory must reference MemoryDetail schema, not an anonymous object");
        assertFalse(memory.has("type"), "anonymous object type field must not be present");
        assertFalse(memory.has("additionalProperties"),
                "anonymous object additionalProperties must not be present");
    }

    @Test
    void memoryEvidenceResponseMustHaveMemoryIdAndRevisionFields() throws Exception {
        JsonNode schemas = ContractTestSupport.JSON.valueToTree(ContractTestSupport.loadOpenApiSpec())
                .at("/components/schemas");
        JsonNode response = required(schemas.get("MemoryEvidenceResponse"), "MemoryEvidenceResponse schema");

        assertEquals(Set.of("requestId", "resultCategory", "memoryId", "currentRevisionId",
                        "revisionNo", "evidenceItems"),
                requiredNames(response),
                "MemoryEvidenceResponse required set mismatch");

        assertEquals("string", response.at("/properties/memoryId/type").asText());
        assertEquals("uuid", response.at("/properties/memoryId/format").asText());
        assertEquals("string", response.at("/properties/currentRevisionId/type").asText());
        assertEquals("uuid", response.at("/properties/currentRevisionId/format").asText());
        assertEquals("integer", response.at("/properties/revisionNo/type").asText());
        assertEquals(1, response.at("/properties/revisionNo/minimum").asInt());
    }

    @Test
    void memoryEvidenceResponseItemsMustUseRefNotAnonymousObject() throws Exception {
        JsonNode schemas = ContractTestSupport.JSON.valueToTree(ContractTestSupport.loadOpenApiSpec())
                .at("/components/schemas");
        JsonNode response = required(schemas.get("MemoryEvidenceResponse"), "MemoryEvidenceResponse schema");

        JsonNode items = required(response.at("/properties/evidenceItems/items"),
                "evidenceItems.items");
        String ref = items.path("$ref").asText();
        assertEquals("#/components/schemas/MemoryEvidenceItem", ref,
                "evidenceItems items must reference MemoryEvidenceItem schema");
        assertFalse(items.has("type"), "anonymous object type field must not be present");
    }

    // ── Mutation rejection tests ────────────────────────────────────────────

    @Test
    void mutationRevertingToAnonymousObjectMustBeDetected() throws Exception {
        Path tempRoot = Files.createTempDirectory("hdm003-read-api-");
        try {
            Path mutated = tempRoot.resolve("anon-memory.yaml");
            String source = ContractTestSupport.readString(ContractTestSupport.OPENAPI_SPEC);
            // Revert memory $ref back to anonymous object
            String mutatedSource = source.replace(
                    "$ref: \"#/components/schemas/MemoryDetail\"",
                    "type: object\n          additionalProperties: false");
            assertFalse(source.equals(mutatedSource), "anonymous object mutation must change the spec");
            Files.writeString(mutated, mutatedSource, StandardCharsets.UTF_8);

            JsonNode mutatedSchemas = ContractTestSupport.JSON.valueToTree(
                    ContractTestSupport.loadYaml(mutated)).at("/components/schemas");
            JsonNode response = required(mutatedSchemas.get("MemoryDetailResponse"),
                    "MemoryDetailResponse schema");
            JsonNode memory = required(response.at("/properties/memory"), "memory property");
            // When reverted to anonymous object, $ref should be absent
            assertTrue(memory.path("$ref").isMissingNode(),
                    "mutated memory must not have $ref");
        } finally {
            deleteTree(tempRoot);
        }
    }

    @Test
    void mutationRemovingRequiredFieldMustBeDetected() throws Exception {
        Path tempRoot = Files.createTempDirectory("hdm003-read-api-");
        try {
            Path mutated = tempRoot.resolve("drop-required.yaml");
            String source = ContractTestSupport.readString(ContractTestSupport.OPENAPI_SPEC);
            // Remove bodyText from MemoryDetail required list.
            // This full required block only appears in MemoryDetail.
            String requiredBlock =
                    "        - memoryId\n" +
                    "        - currentRevisionId\n" +
                    "        - revisionNo\n" +
                    "        - state\n" +
                    "        - memoryType\n" +
                    "        - perspectiveActorId\n" +
                    "        - bodyText\n" +
                    "        - updatedAt\n" +
                    "        - evidenceCount";
            assertTrue(source.contains(requiredBlock),
                    "MemoryDetail required block must be present in source");
            String replaced = requiredBlock.replace("\n        - bodyText", "");
            String mutatedSource = source.replace(requiredBlock, replaced);
            assertFalse(source.equals(mutatedSource),
                    "required mutation must change the spec");
            Files.writeString(mutated, mutatedSource, StandardCharsets.UTF_8);

            JsonNode mutatedSchemas = ContractTestSupport.JSON.valueToTree(
                    ContractTestSupport.loadYaml(mutated)).at("/components/schemas");
            JsonNode detail = required(mutatedSchemas.get("MemoryDetail"),
                    "MemoryDetail schema");
            Set<String> actualRequired = requiredNames(detail);
            assertFalse(actualRequired.contains("bodyText"),
                    "bodyText must be removed from required for this mutation test");
            assertTrue(actualRequired.contains("evidenceCount"),
                    "evidenceCount must still be required after mutation");
        } finally {
            deleteTree(tempRoot);
        }
    }

    @Test
    void mutationAddingForbiddenFieldMustBeDetected() throws Exception {
        Path tempRoot = Files.createTempDirectory("hdm003-read-api-");
        try {
            Path mutated = tempRoot.resolve("forbidden-field.yaml");
            String source = ContractTestSupport.readString(ContractTestSupport.OPENAPI_SPEC);
            // Insert objectRef forbidden field into MemoryDetail properties, right after uncertaintyCode
            String mutatedSource = source.replace(
                    "        uncertaintyCode:\n          type: string\n      additionalProperties: false",
                    "        uncertaintyCode:\n          type: string\n        objectRef:\n          type: string\n      additionalProperties: false");
            assertFalse(source.equals(mutatedSource), "forbidden field mutation must change the spec");
            Files.writeString(mutated, mutatedSource, StandardCharsets.UTF_8);

            JsonNode mutatedSchemas = ContractTestSupport.JSON.valueToTree(
                    ContractTestSupport.loadYaml(mutated)).at("/components/schemas");
            JsonNode detail = required(mutatedSchemas.get("MemoryDetail"), "MemoryDetail schema");
            Set<String> props = propertyNames(detail.get("properties"));
            assertTrue(props.contains("objectRef"),
                    "mutated MemoryDetail must contain forbidden field objectRef");

            // Verify the forbidden field is NOT in the production spec
            JsonNode prodSchemas = ContractTestSupport.JSON.valueToTree(
                    ContractTestSupport.loadOpenApiSpec()).at("/components/schemas");
            JsonNode prodDetail = required(prodSchemas.get("MemoryDetail"), "MemoryDetail schema");
            Set<String> prodProps = propertyNames(prodDetail.get("properties"));
            assertFalse(prodProps.contains("objectRef"),
                    "production MemoryDetail must NOT contain forbidden field objectRef");
        } finally {
            deleteTree(tempRoot);
        }
    }

    // ── Spec-baseline byte identity ─────────────────────────────────────────

    @Test
    void formalSpecAndBaselineMustBeByteIdentical() throws Exception {
        Path spec = ContractTestSupport.OPENAPI_SPEC;
        Path baseline = ContractTestSupport.FIXTURES_DIR.resolve("baseline.yaml");
        String specContent = ContractTestSupport.readString(spec);
        String baselineContent = ContractTestSupport.readString(baseline);
        assertEquals(specContent, baselineContent,
                "formal spec and compatibility baseline must be byte-identical");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static JsonNode required(JsonNode node, String label) {
        if (node == null || node.isMissingNode()) {
            throw new AssertionError("Missing " + label);
        }
        return node;
    }

    private static Set<String> propertyNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        if (node != null && !node.isMissingNode()) {
            node.propertyNames().forEach(names::add);
        }
        return names;
    }

    private static Set<String> requiredNames(JsonNode schemaNode) {
        Set<String> names = new LinkedHashSet<>();
        JsonNode required = schemaNode.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode entry : required) {
                names.add(entry.asText());
            }
        }
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
}
