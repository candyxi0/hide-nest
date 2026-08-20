package io.github.candyxi0.hidenest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1ContextPackException;
import io.github.candyxi0.hidenest.application.model.LocalV1ContextPackRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Task43A H1-01: the transport mapper must distinguish a truly absent optional policy field
 * (resolves to the contract default) from a field that is present but explicitly {@code null}
 * (must be rejected with REQUEST_SCHEMA_INVALID before any embedding or audit work). This is a
 * single-factor unit test: it proves the mapper rejects at the boundary without invoking the
 * coordinator, embedding or database.
 */
class LocalV1ContextPackRequestMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UUID1 = "11111111-1111-1111-1111-111111111111";
    private static final String UUID2 = "22222222-2222-2222-2222-222222222222";

    @Test
    void absentPolicyFieldsResolveToContractDefaults() throws Exception {
        LocalV1ContextPackRequest request = map(
                """
                {
                  "threadId": "%s",
                  "turnId": "%s",
                  "purpose": "recall",
                  "query": "what did we decide"
                }
                """
                .formatted(UUID1, UUID2));
        assertEquals(3, request.maxResults());
        assertEquals(0.6d, request.minScore());
    }

    @Test
    void explicitNullMaxResultsIsRejected() throws Exception {
        assertSchemaInvalid(
                """
                {
                  "threadId": "%s",
                  "turnId": "%s",
                  "purpose": "recall",
                  "query": "q",
                  "maxResults": null
                }
                """
                .formatted(UUID1, UUID2));
    }

    @Test
    void explicitNullMinScoreIsRejected() throws Exception {
        assertSchemaInvalid(
                """
                {
                  "threadId": "%s",
                  "turnId": "%s",
                  "purpose": "recall",
                  "query": "q",
                  "minScore": null
                }
                """
                .formatted(UUID1, UUID2));
    }

    @Test
    void explicitNullBothPolicyFieldsIsRejected() throws Exception {
        assertSchemaInvalid(
                """
                {
                  "threadId": "%s",
                  "turnId": "%s",
                  "purpose": "recall",
                  "query": "q",
                  "maxResults": null,
                  "minScore": null
                }
                """
                .formatted(UUID1, UUID2));
    }

    @Test
    void explicitValidValuesAreParsed() throws Exception {
        LocalV1ContextPackRequest request = map(
                """
                {
                  "threadId": "%s",
                  "turnId": "%s",
                  "purpose": "recall",
                  "query": "q",
                  "maxResults": 5,
                  "minScore": 0.4
                }
                """
                .formatted(UUID1, UUID2));
        assertEquals(5, request.maxResults());
        assertEquals(0.4d, request.minScore());
    }

    @Test
    void wrongTypeIsRejected() throws Exception {
        assertSchemaInvalid(
                """
                {
                  "threadId": "%s",
                  "turnId": "%s",
                  "purpose": "recall",
                  "query": "q",
                  "maxResults": "three"
                }
                """
                .formatted(UUID1, UUID2));
        assertSchemaInvalid(
                """
                {
                  "threadId": "%s",
                  "turnId": "%s",
                  "purpose": "recall",
                  "query": "q",
                  "minScore": true
                }
                """
                .formatted(UUID1, UUID2));
    }

    private static LocalV1ContextPackRequest map(String body) throws Exception {
        JsonNode node = JSON.readTree(body);
        return LocalV1ContextPackRequestMapper.map(node);
    }

    private static void assertSchemaInvalid(String body) {
        LocalV1ContextPackException ex = assertThrows(
                LocalV1ContextPackException.class, () -> map(body));
        assertEquals(LocalV1ContextPackException.Code.REQUEST_SCHEMA_INVALID, ex.code());
    }
}
