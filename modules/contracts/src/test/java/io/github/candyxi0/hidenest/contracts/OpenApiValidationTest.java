package io.github.candyxi0.hidenest.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Category 1: OpenAPI spec must be valid 3.1.2 and parseable.
 * If this test passes, the spec is structurally valid YAML with correct openapi version.
 */
class OpenApiValidationTest {

    @Test
    void specMustBeOpenApi312() throws Exception {
        Map<String, Object> spec = ContractTestSupport.loadOpenApiSpec();
        assertNotNull(spec, "OpenAPI spec must be parseable");

        String version = (String) spec.get("openapi");
        assertEquals("3.1.2", version, "OpenAPI version must be exactly 3.1.2");
    }

    @Test
    void specMustHaveRequiredInfoFields() throws Exception {
        Map<String, Object> spec = ContractTestSupport.loadOpenApiSpec();

        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) spec.get("info");
        assertNotNull(info, "info section must exist");
        assertNotNull(info.get("title"), "info.title must be set");
        assertNotNull(info.get("version"), "info.version must be set");
    }

    @Test
    void specMustHavePaths() throws Exception {
        Map<String, Object> spec = ContractTestSupport.loadOpenApiSpec();

        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        assertNotNull(paths, "paths section must exist");
        assertEquals(true, paths.size() > 0, "paths must contain at least one path");
    }

    @Test
    void specMustHaveComponentsSchemas() throws Exception {
        Map<String, Object> spec = ContractTestSupport.loadOpenApiSpec();

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        assertNotNull(components, "components section must exist");

        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        assertNotNull(schemas, "components.schemas must exist");
    }
}
