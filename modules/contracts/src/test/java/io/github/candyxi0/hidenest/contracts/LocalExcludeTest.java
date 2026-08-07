package io.github.candyxi0.hidenest.contracts;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Excludes local, actuator, and debug surfaces from the parsed path graph. */
class LocalExcludeTest {

    @Test
    void forbiddenPathFamiliesMustNotExist() throws Exception {
        JsonNode spec = ContractTestSupport.JSON.valueToTree(ContractTestSupport.loadOpenApiSpec());
        for (String path : spec.get("paths").propertyNames()) {
            assertFalse(path.startsWith("/local/"));
            assertFalse(path.startsWith("/actuator/"));
            assertFalse(path.startsWith("/health"));
            assertFalse(path.startsWith("/metrics"));
            assertFalse(path.startsWith("/debug"));
            assertFalse(path.startsWith("/trace"));
        }
    }
}
