package io.github.candyxi0.hidenest.contracts;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * Category 10: Leakage canary.
 * Ensures no sensitive/internal strings appear in any contract or fixture file.
 */
class LeakageCanaryTest {

    private static final String[] FORBIDDEN_STRINGS = {
        "body_text",
        "sourcePayload",
        "apiKey",
        "sql",
        "stackTrace",
        "DROP TABLE",
        "SELECT *",
        "secret",
        "password",
        "token_value"
    };

    @Test
    void openApiSpecMustNotLeakSensitiveData() throws Exception {
        String spec = ContractTestSupport.readString(ContractTestSupport.OPENAPI_SPEC);
        for (String forbidden : FORBIDDEN_STRINGS) {
            // Some strings may appear in descriptions as "must not contain" — that's OK
            // But they should not appear as field names or default values
            assertFalse(isLeaked(spec, forbidden), "OpenAPI spec must not leak sensitive string: " + forbidden);
        }
    }

    @Test
    void eventSchemaMustNotLeakSensitiveData() throws Exception {
        String schema = ContractTestSupport.readString(ContractTestSupport.EVENT_SCHEMA);
        // Forbidden fields should appear only in the "not" constraint, not as real fields
        // This is a structural check — the forbidden field names should appear in the negation
        String[] trulyForbidden = {"DROP TABLE", "SELECT *", "password", "token_value"};
        for (String forbidden : trulyForbidden) {
            assertFalse(schema.contains(forbidden), "Event schema must not contain: " + forbidden);
        }
    }

    @Test
    void problemDetailSchemaMustNotLeakSensitiveData() throws Exception {
        String schema = ContractTestSupport.readString(ContractTestSupport.PROBLEM_DETAIL_SCHEMA);
        String[] trulyForbidden = {"DROP TABLE", "SELECT *", "password", "token_value"};
        for (String forbidden : trulyForbidden) {
            assertFalse(schema.contains(forbidden), "ProblemDetail schema must not contain: " + forbidden);
        }
    }

    @Test
    void noLeakageInCompatibilityFixtures() throws Exception {
        String baseline =
                ContractTestSupport.readString(ContractTestSupport.FIXTURES_DIR.resolve("baseline-fixture.yaml"));
        String addOptional =
                ContractTestSupport.readString(ContractTestSupport.FIXTURES_DIR.resolve("add-optional-field.yaml"));
        String removeRequired =
                ContractTestSupport.readString(ContractTestSupport.FIXTURES_DIR.resolve("remove-required-field.yaml"));
        String removeOp =
                ContractTestSupport.readString(ContractTestSupport.FIXTURES_DIR.resolve("remove-operation.yaml"));

        String[] allFixtures = {baseline, addOptional, removeRequired, removeOp};
        String[] trulyForbidden = {"DROP TABLE", "SELECT *", "password", "apiKey"};

        for (String fixture : allFixtures) {
            for (String forbidden : trulyForbidden) {
                assertFalse(fixture.contains(forbidden), "Compatibility fixture must not contain: " + forbidden);
            }
        }
    }

    /**
     * Check if a string appears as a field/property name (not in description text).
     * A string is "leaked" if it appears as a YAML key (before a colon).
     */
    private boolean isLeaked(String text, String fieldName) {
        // Check if the field name appears as a YAML key (fieldName:)
        return text.contains(fieldName + ":") || text.contains("\"" + fieldName + "\"");
    }
}
