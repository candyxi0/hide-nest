package io.github.candyxi0.hidenest.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Structural assertions are sourced from the canonical Inventory snapshot. */
class EventSchemaTest {

    @Test
    void rootMustReferencePinkEventEnvelope() throws Exception {
        JsonNode schema = ContractTestSupport.loadJson(ContractTestSupport.EVENT_SCHEMA);
        assertEquals("#/$defs/PinkEventEnvelope", schema.get("$ref").asText());
        JsonNode envelope = schema.at("/$defs/PinkEventEnvelope");
        assertTrue(envelope.get("additionalProperties").isBoolean());
        assertFalse(envelope.get("additionalProperties").asBoolean());
    }

    @Test
    void eventTypeMustMatchInventoryExactly() throws Exception {
        JsonNode schemaValues =
                ContractTestSupport.loadJson(ContractTestSupport.EVENT_SCHEMA).at("/$defs/EventType/enum");
        JsonNode inventory = ContractTestSupport.loadInventory();
        JsonNode expectedValues = null;
        for (JsonNode eventSchema : inventory.at("/schemas/eventSchemas")) {
            if ("EventType".equals(eventSchema.get("name").asText())) {
                expectedValues = eventSchema.get("values");
            }
        }
        assertNotNull(expectedValues, "Inventory must define EventType");
        assertEquals(expectedValues, schemaValues);
        Set<String> actual = new HashSet<>();
        for (JsonNode value : schemaValues) actual.add(value.asText());
        assertEquals(12, actual.size());
    }

    @Test
    void envelopeForbiddenFieldsMustBeRejectedByClosedSchema() throws Exception {
        JsonNode schema = ContractTestSupport.loadJson(ContractTestSupport.EVENT_SCHEMA);
        JsonNode forbidden = null;
        for (JsonNode eventSchema : ContractTestSupport.loadInventory().at("/schemas/eventSchemas")) {
            if ("PinkEventEnvelope".equals(eventSchema.get("name").asText())) {
                forbidden = eventSchema.get("forbiddenFields");
            }
        }
        assertNotNull(forbidden);
        JsonNode properties = schema.at("/$defs/PinkEventEnvelope/properties");
        for (JsonNode field : forbidden) {
            assertFalse(properties.has(field.asText()), "forbidden field became a real property");
        }
    }
}
