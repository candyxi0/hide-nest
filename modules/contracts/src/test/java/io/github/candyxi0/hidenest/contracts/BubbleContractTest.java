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

/** Frozen HTTP closure and minimal-projection contract for Bubble V1. */
class BubbleContractTest {

    private static final String REQUEST_REF =
            "contracts/openapi/hide-nest-api.yaml#/components/schemas/BubbleResolveRequest";
    private static final String RESPONSE_REF =
            "contracts/openapi/hide-nest-api.yaml#/components/schemas/BubbleResolveResponse";

    @Test
    void resolveRequestIsExactlyFourRequiredFieldsAndClosed() throws Exception {
        Map<String, Object> schema = schema("BubbleResolveRequest");
        assertEquals(
                Set.of("spaceKey", "roomKey", "turnKey", "queryText"), Set.copyOf(castList(schema.get("required"))));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
        assertEquals(
                Set.of("spaceKey", "roomKey", "turnKey", "queryText"),
                castMap(schema.get("properties")).keySet());

        JsonNode forbiddenPolicy = ContractTestSupport.JSON.readTree("""
                {"spaceKey":"s","roomKey":"r","turnKey":"t","queryText":"q","minScore":0.4}
                """);
        assertFalse(
                ContractTestSupport.validateSchema(REQUEST_REF, forbiddenPolicy).isEmpty());
    }

    @Test
    void responseAndItemExposeOnlyFrozenProjection() throws Exception {
        Map<String, Object> response = schema("BubbleResolveResponse");
        assertEquals(
                Set.of("status", "items"), castMap(response.get("properties")).keySet());
        assertEquals(Boolean.FALSE, response.get("additionalProperties"));
        Map<String, Object> item = schema("BubbleItem");
        assertEquals(
                Set.of("bodyText", "memoryType", "evidenceAgeDays"),
                castMap(item.get("properties")).keySet());
        assertEquals(Boolean.FALSE, item.get("additionalProperties"));

        for (String payload : List.of(
                "{\"status\":\"NO_MATCH\",\"items\":[]}",
                "{\"status\":\"BUBBLE_READY\",\"items\":[{\"bodyText\":\"b\",\"memoryType\":\"EVENT\",\"evidenceAgeDays\":3}]}")) {
            List<Error> errors =
                    ContractTestSupport.validateSchema(RESPONSE_REF, ContractTestSupport.JSON.readTree(payload));
            assertTrue(errors.isEmpty(), "valid Bubble response rejected: " + errors);
        }
        for (String payload : List.of(
                "{\"status\":\"BUBBLE_READY\",\"items\":[]}",
                "{\"status\":\"NO_MATCH\",\"items\":[{\"bodyText\":\"b\",\"memoryType\":\"EVENT\",\"evidenceAgeDays\":0}]}")) {
            List<Error> errors =
                    ContractTestSupport.validateSchema(RESPONSE_REF, ContractTestSupport.JSON.readTree(payload));
            assertFalse(errors.isEmpty(), "result/item closure violation must be rejected");
        }
    }

    @Test
    void bothOperationsAreBearerOnlyNoStoreAndHaveNoIdempotencyHeader() throws Exception {
        Map<String, Object> spec = ContractTestSupport.loadOpenApiSpec();
        Map<String, Object> paths = castMap(spec.get("paths"));
        for (String path : List.of("/bubbles/resolve", "/bubbles/rooms/purge")) {
            Map<String, Object> operation = castMap(castMap(paths.get(path)).get("post"));
            assertNotNull(operation);
            assertFalse(operation.containsKey("parameters"));
            List<Map<String, Object>> security = castList(operation.get("security"));
            assertEquals(Set.of("bearerSession"), security.getFirst().keySet());
            Map<String, Object> ok = castMap(castMap(operation.get("responses")).get("200"));
            assertEquals(
                    "no-store",
                    castMap(castMap(castMap(ok.get("headers")).get("Cache-Control"))
                                    .get("schema"))
                            .get("const"));
        }
    }

    private static Map<String, Object> schema(String name) throws Exception {
        Map<String, Object> spec = ContractTestSupport.loadOpenApiSpec();
        Map<String, Object> result =
                castMap(castMap(castMap(spec.get("components")).get("schemas")).get(name));
        assertNotNull(result, name + " schema must exist");
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<String, T> castMap(Object value) {
        return (Map<String, T>) value;
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> castList(Object value) {
        return (List<T>) value;
    }
}
