package io.github.candyxi0.hidenest.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Shared, fail-closed helpers for contract tests. */
final class ContractTestSupport {

    static final Path REPO_ROOT = Path.of("").toAbsolutePath().getParent().getParent();
    static final Path OPENAPI_SPEC = REPO_ROOT.resolve("contracts/openapi/hide-nest-api.yaml");
    static final Path PROBLEM_DETAIL_SCHEMA = REPO_ROOT.resolve("contracts/openapi/problem-detail.schema.json");
    static final Path EVENT_SCHEMA = REPO_ROOT.resolve("contracts/events/pink-event-v1.schema.json");
    static final Path INVENTORY = REPO_ROOT.resolve("contracts/inventory/ContractInventory-HDM-003-v0.1.json");
    static final Path FIXTURE_MANIFEST =
            REPO_ROOT.resolve("packages/ui-contract-fixtures/src/fixtures/fixture-manifest.json");
    static final Path FIXTURES_DIR = REPO_ROOT.resolve("contracts/compatibility-fixtures");
    static final ObjectMapper JSON = new ObjectMapper();

    private ContractTestSupport() {}

    static Map<String, Object> loadOpenApiSpec() throws IOException {
        try (InputStream is = Files.newInputStream(OPENAPI_SPEC)) {
            return new Yaml().load(is);
        }
    }

    static Map<String, Object> loadYaml(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return new Yaml().load(is);
        }
    }

    static String readString(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    static JsonNode loadJson(Path path) throws IOException {
        return JSON.readTree(path);
    }

    static JsonNode loadInventory() throws IOException {
        return loadJson(INVENTORY);
    }

    static JsonNode loadFixtureManifest() throws IOException {
        return loadJson(FIXTURE_MANIFEST);
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            fail("Missing textual field '" + field + "' in " + node);
        }
        return value.asText();
    }

    static List<Error> validateFixture(JsonNode fixtureEntry) throws IOException {
        String schemaRef = text(fixtureEntry, "schemaRef");
        Path fixturePath = REPO_ROOT.resolve(text(fixtureEntry, "fixturePath"));
        if (!Files.isRegularFile(fixturePath)) {
            fail("Fixture path does not exist: " + fixturePath);
        }
        return validateSchema(schemaRef, loadJson(fixturePath));
    }

    static List<Error> validateSchema(String schemaRef, JsonNode instance) throws IOException {
        JsonNode schemaNode = schemaForRef(schemaRef);
        Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaNode);
        return schema.validate(instance);
    }

    private static JsonNode schemaForRef(String schemaRef) throws IOException {
        int hash = schemaRef.indexOf('#');
        String pathPart = hash < 0 ? schemaRef : schemaRef.substring(0, hash);
        String fragment = hash < 0 ? "" : schemaRef.substring(hash + 1);
        Path schemaPath = REPO_ROOT.resolve(pathPart);
        if (fragment.isEmpty()) {
            return loadJson(schemaPath);
        }
        if (!pathPart.endsWith("hide-nest-api.yaml")) {
            fail("Only OpenAPI component refs may use a fragment here: " + schemaRef);
        }
        Map<String, Object> openApi = loadYaml(schemaPath);
        Object selected = openApi;
        for (String part : fragment.split("/")) {
            if (part.isEmpty()) continue;
            if (!(selected instanceof Map<?, ?>)) {
                fail("Unresolvable schemaRef: " + schemaRef);
            }
            Map<?, ?> selectedMap = (Map<?, ?>) selected;
            if (!selectedMap.containsKey(part)) {
                fail("Unresolvable schemaRef: " + schemaRef);
            }
            selected = selectedMap.get(part);
        }
        Object resolved = resolveOpenApiRefs(selected, openApi, new HashSet<>());
        return JSON.valueToTree(resolved);
    }

    @SuppressWarnings("unchecked")
    private static Object resolveOpenApiRefs(Object value, Map<String, Object> root, Set<String> stack) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                map.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            Object refObject = map.get("$ref");
            if (refObject instanceof String ref && ref.startsWith("#/components/schemas/")) {
                if (!stack.add(ref)) {
                    fail("Cyclic OpenAPI schema reference: " + ref);
                }
                String name = ref.substring("#/components/schemas/".length());
                Map<String, Object> components = (Map<String, Object>) root.get("components");
                Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
                Object target = schemas.get(name);
                if (target == null) {
                    fail("Missing OpenAPI schema component: " + name);
                }
                Object resolved = resolveOpenApiRefs(target, root, stack);
                if (resolved instanceof Map<?, ?> resolvedMap) {
                    for (Map.Entry<?, ?> entry : resolvedMap.entrySet()) {
                        map.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                map.remove("$ref");
                stack.remove(ref);
            }
            for (Map.Entry<String, Object> entry : new ArrayList<>(map.entrySet())) {
                entry.setValue(resolveOpenApiRefs(entry.getValue(), root, stack));
            }
            return map;
        }
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>();
            for (Object item : list) {
                resolved.add(resolveOpenApiRefs(item, root, stack));
            }
            return resolved;
        }
        return value;
    }
}
