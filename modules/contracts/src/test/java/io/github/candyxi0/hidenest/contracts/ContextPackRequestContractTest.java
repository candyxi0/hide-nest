package io.github.candyxi0.hidenest.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.Error;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Task43A: ContextPackRequest optional maxResults/minScore extension must be a
 * true backward-compatible request extension. These tests prove the real
 * compatibility semantics independently of the openapi-diff tool verdict:
 * old four-field requests remain valid, the new fields are optional with exact
 * bounds, and unknown fields are still rejected by the closed schema.
 */
class ContextPackRequestContractTest {

    private static final String SCHEMA_REF =
            "contracts/openapi/hide-nest-api.yaml#/components/schemas/ContextPackRequest";

    @Test
    void requiredSetRemainsExactlyTheOriginalFourFields() throws Exception {
        Map<String, Object> schema = contextPackRequestSchema();
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertNotNull(required, "ContextPackRequest.required must exist");
        assertEquals(
                Set.of("threadId", "turnId", "purpose", "query"),
                Set.copyOf(required),
                "required set must remain exactly the original four fields");
        assertFalse(required.contains("maxResults"), "maxResults must not be required");
        assertFalse(required.contains("minScore"), "minScore must not be required");
    }

    @Test
    void schemaRemainsClosed() throws Exception {
        Map<String, Object> schema = contextPackRequestSchema();
        assertEquals(
                Boolean.FALSE,
                schema.get("additionalProperties"),
                "ContextPackRequest must remain additionalProperties: false");
    }

    @Test
    void maxResultsHasExactOptionalIntegerBounds() throws Exception {
        Map<String, Object> maxResults = property("maxResults");
        assertEquals("integer", maxResults.get("type"));
        assertEquals(1, ((Number) maxResults.get("minimum")).intValue());
        assertEquals(5, ((Number) maxResults.get("maximum")).intValue());
        assertEquals(3, ((Number) maxResults.get("default")).intValue());
    }

    @Test
    void minScoreHasExactOptionalNumberBounds() throws Exception {
        Map<String, Object> minScore = property("minScore");
        assertEquals("number", minScore.get("type"));
        assertEquals("double", minScore.get("format"));
        assertEquals(0.4d, ((Number) minScore.get("minimum")).doubleValue());
        assertEquals(1.0d, ((Number) minScore.get("maximum")).doubleValue());
        assertEquals(0.6d, ((Number) minScore.get("default")).doubleValue());
    }

    @Test
    void oldFourFieldRequestRemainsValid() throws Exception {
        JsonNode oldRequest = ContractTestSupport.JSON.readTree(
                """
                {
                  "threadId": "11111111-1111-1111-1111-111111111111",
                  "turnId": "22222222-2222-2222-2222-222222222222",
                  "purpose": "recall project decisions",
                  "query": "what did we decide about storage"
                }
                """);
        List<Error> errors = ContractTestSupport.validateSchema(SCHEMA_REF, oldRequest);
        assertTrue(errors.isEmpty(), "old four-field request must remain valid: " + errors);
    }

    @Test
    void newSixFieldRequestIsValid() throws Exception {
        JsonNode newRequest = ContractTestSupport.JSON.readTree(
                """
                {
                  "threadId": "11111111-1111-1111-1111-111111111111",
                  "turnId": "22222222-2222-2222-2222-222222222222",
                  "purpose": "recall project decisions",
                  "query": "what did we decide about storage",
                  "maxResults": 5,
                  "minScore": 0.4
                }
                """);
        List<Error> errors = ContractTestSupport.validateSchema(SCHEMA_REF, newRequest);
        assertTrue(errors.isEmpty(), "new six-field request must be valid: " + errors);
    }

    @Test
    void unknownFieldIsStillRejected() throws Exception {
        JsonNode request = ContractTestSupport.JSON.readTree(
                """
                {
                  "threadId": "11111111-1111-1111-1111-111111111111",
                  "turnId": "22222222-2222-2222-2222-222222222222",
                  "purpose": "recall project decisions",
                  "query": "what did we decide about storage",
                  "excludeTerms": ["billing"]
                }
                """);
        List<Error> errors = ContractTestSupport.validateSchema(SCHEMA_REF, request);
        assertFalse(errors.isEmpty(), "unknown field must still be rejected by the closed schema");
    }

    @Test
    void outOfRangeNewFieldsAreRejected() throws Exception {
        String base =
                """
                {
                  "threadId": "11111111-1111-1111-1111-111111111111",
                  "turnId": "22222222-2222-2222-2222-222222222222",
                  "purpose": "recall project decisions",
                  "query": "what did we decide about storage",
                """;
        for (String extra : new String[] {
            "\"maxResults\": 0", "\"maxResults\": 6", "\"minScore\": 0.39", "\"minScore\": 1.01"
        }) {
            JsonNode request = ContractTestSupport.JSON.readTree(base + extra + "}");
            List<Error> errors = ContractTestSupport.validateSchema(SCHEMA_REF, request);
            assertFalse(errors.isEmpty(), "out-of-range field must be rejected: " + extra);
        }
    }

    private static Map<String, Object> contextPackRequestSchema() throws Exception {
        Map<String, Object> spec = ContractTestSupport.loadOpenApiSpec();
        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) schemas.get("ContextPackRequest");
        assertNotNull(schema, "ContextPackRequest schema must exist");
        return schema;
    }

    private static Map<String, Object> property(String name) throws Exception {
        Map<String, Object> schema = contextPackRequestSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> property = (Map<String, Object>) properties.get(name);
        assertNotNull(property, "ContextPackRequest.properties." + name + " must exist");
        return property;
    }
}
