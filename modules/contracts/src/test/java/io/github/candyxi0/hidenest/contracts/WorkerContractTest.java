package io.github.candyxi0.hidenest.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Offline, fail-closed contract and fixture judge for Core to Worker v1. */
class WorkerContractTest {

    private static final Path WORKER_ROOT = ContractTestSupport.REPO_ROOT.resolve("contracts/worker");
    private static final Path MANIFEST = WORKER_ROOT.resolve("fixture-manifest.json");
    private static final String TASK_SCHEMA = "contracts/worker/worker-task-envelope-v1.schema.json";
    private static final String RETRIEVAL_SCHEMA = "contracts/worker/retrieval-result-v1.schema.json";
    private static final String FORMATION_SCHEMA = "contracts/worker/formation-result-v1.schema.json";
    private static final String INDEX_SCHEMA = "contracts/worker/index-projection-result-v1.schema.json";

    @Test
    void manifestFixturesAreValidatedByTheirReferencedRealSchemas() throws Exception {
        JsonNode manifest = ContractTestSupport.loadJson(MANIFEST);
        Set<String> ids = new HashSet<>();
        Set<String> paths = new HashSet<>();
        JsonNode fixtures = manifest.path("fixtures");
        assertTrue(fixtures.isArray() && !fixtures.isEmpty(), "worker fixture manifest must be non-empty");
        for (JsonNode entry : fixtures) {
            String id = ContractTestSupport.text(entry, "id");
            assertTrue(ids.add(id), "duplicate worker fixture id: " + id);
            String path = ContractTestSupport.text(entry, "fixturePath");
            assertTrue(paths.add(path), "duplicate worker fixture path: " + path);
            Path fixturePath = ContractTestSupport.REPO_ROOT.resolve(path);
            assertTrue(Files.isRegularFile(fixturePath), "missing worker fixture: " + path);
            List<Error> errors = validateWorkerSchema(
                    ContractTestSupport.text(entry, "schemaRef"), ContractTestSupport.loadJson(fixturePath));
            if (entry.path("expectedValid").asBoolean()) {
                assertTrue(errors.isEmpty(), id + " unexpectedly failed: " + errors);
            } else {
                assertFalse(errors.isEmpty(), id + " unexpectedly passed schema validation");
                String keyword = ContractTestSupport.text(entry, "expectedKeyword");
                String instancePath = ContractTestSupport.text(entry, "expectedInstancePath");
                assertTrue(
                        errors.stream()
                                .anyMatch(error -> keyword.equals(error.getKeyword())
                                        && instancePath.equals(String.valueOf(error.getInstanceLocation()))),
                        id + " did not fail at " + keyword + " / " + instancePath + ": " + errors);
            }
        }
        assertEqualsPathSet(paths);
    }

    @Test
    void retrievalSeparatesNoSupportFromExecutionFailure() throws Exception {
        JsonNode noSupport = fixture("result.retrieval.no-support.valid");
        JsonNode failed = fixture("result.retrieval.failed.valid");
        assertTrue("NO_SUPPORT".equals(noSupport.path("semanticStatus").asText()));
        assertTrue("COMPLETED".equals(noSupport.path("executionStatus").asText()));
        assertTrue("NOT_EVALUATED".equals(failed.path("semanticStatus").asText()));
        assertTrue("FAILED".equals(failed.path("executionStatus").asText()));
    }

    @Test
    void candidateRefsNeverContainModelReady正文AndCrossWorldRefsAreRejectedByFixtureRule() throws Exception {
        JsonNode sufficient = fixture("result.retrieval.sufficient.valid");
        for (JsonNode candidate : sufficient.path("candidateRefs")) {
            assertFalse(candidate.has("content"), "candidate ref must not contain正文");
            assertFalse(candidate.has("body"), "candidate ref must not contain正文");
            assertTrue(sufficient
                    .path("worldRef")
                    .asText()
                    .equals(candidate.path("worldRef").asText()));
        }
        String source = ContractTestSupport.readString(WORKER_ROOT.resolve("fixtures/valid/retrieval-sufficient.json"));
        JsonNode crossWorld = ContractTestSupport.JSON.readTree(source.replace(
                "\"worldRef\":\"world:alpha\",\"projectionGeneration\"",
                "\"worldRef\":\"world:other\",\"projectionGeneration\""));
        assertTrue(hasCrossWorldReference(crossWorld), "fixture rule must detect cross-world refs");
    }

    @Test
    void formationExitKindsKeepNoChangeDistinctFromIncompleteAndFailure() throws Exception {
        assertTrue("NO_LONG_TERM_CHANGE"
                .equals(fixture("result.formation.no-change.valid")
                        .path("resultType")
                        .asText()));
        assertTrue("SOURCE_INCOMPLETE"
                .equals(fixture("result.formation.source-incomplete.valid")
                        .path("resultType")
                        .asText()));
        assertTrue("BUDGET_EXHAUSTED"
                .equals(fixture("result.formation.budget-exhausted.valid")
                        .path("resultType")
                        .asText()));
        assertFalse(fixture("result.formation.no-change.valid").has("writeSet"));
        assertFalse(fixture("result.formation.source-incomplete.valid").has("writeSet"));
    }

    @Test
    void oldOpenCandidateContractIsKilledByTheNewClosedSchema() throws Exception {
        String source = ContractTestSupport.readString(WORKER_ROOT.resolve("fixtures/valid/retrieval-sufficient.json"));
        JsonNode result = ContractTestSupport.JSON.readTree(source.replace(
                "\"navigation\":\"revision:current-1\"",
                "\"navigation\":\"revision:current-1\",\"body\":\"must-be-rejected\""));
        List<Error> errors = validateWorkerSchema("contracts/worker/retrieval-result-v1.schema.json", result);
        assertFalse(errors.isEmpty(), "old candidate-with-body contract must be rejected");
    }

    @Test
    void workerJsonFixturePathSetHasNoOrphansOrManifestGaps() throws Exception {
        JsonNode manifest = ContractTestSupport.loadJson(MANIFEST);
        Set<String> manifestPaths = new HashSet<>();
        for (JsonNode entry : manifest.path("fixtures"))
            manifestPaths.add(ContractTestSupport.text(entry, "fixturePath"));
        Set<String> actualPaths = new HashSet<>();
        for (String kind : List.of("valid", "invalid")) {
            Path root = WORKER_ROOT.resolve("fixtures").resolve(kind);
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .forEach(path -> actualPaths.add(ContractTestSupport.REPO_ROOT
                                .relativize(path)
                                .toString()
                                .replace('\\', '/')));
            }
        }
        assertTrue(
                manifestPaths.equals(actualPaths),
                "worker fixture manifest/path set mismatch: manifest=" + manifestPaths + " actual=" + actualPaths);
    }

    @Test
    void r2MemoryContentShapesAreExactlyFourAndEveryLegacyTypeIsRejected() throws Exception {
        assertEquals(
                Set.of("EVENT", "CLAIM", "QUOTE", "UNDERSTANDING"),
                Set.of(
                        fixture("result.retrieval.sufficient.valid")
                                .at("/selected/0/type")
                                .asText(),
                        fixture("result.formation.create.valid")
                                .at("/writeSet/0/type")
                                .asText(),
                        fixture("result.formation.revise.valid")
                                .at("/writeSet/0/type")
                                .asText(),
                        fixture("result.formation.supersede.valid")
                                .at("/writeSet/0/type")
                                .asText()));
        for (String legacy : List.of("FACT", "DECISION", "PREFERENCE", "RELATION", "EPISODE")) {
            ObjectNode retrieval = mutableFixture("result.retrieval.sufficient.valid");
            ((ObjectNode) retrieval.at("/selected/0")).put("type", legacy);
            assertRejected(RETRIEVAL_SCHEMA, retrieval, "legacy retrieval type " + legacy);
            ObjectNode formation = mutableFixture("result.formation.create.valid");
            ((ObjectNode) formation.at("/writeSet/0")).put("type", legacy);
            assertRejected(FORMATION_SCHEMA, formation, "legacy formation type " + legacy);
        }
    }

    @Test
    void r2MultiAnchorWriteSetRequiresSourceVersionAndExactLocators() throws Exception {
        JsonNode anchors = fixture("result.formation.create.valid").at("/writeSet/0/sourceAnchors");
        assertEquals(
                "UNDERSTANDING",
                fixture("result.formation.create.valid").at("/writeSet/0/type").asText());
        assertEquals(2, anchors.size(), "one memory change must support two disjoint source anchors");
        assertFalse(anchors.get(0)
                .path("locator")
                .asText()
                .equals(anchors.get(1).path("locator").asText()));

        ObjectNode singular = mutableFixture("result.formation.create.valid");
        ObjectNode singularItem = (ObjectNode) singular.at("/writeSet/0");
        singularItem.set("sourceAnchor", singularItem.at("/sourceAnchors/0").deepCopy());
        singularItem.remove("sourceAnchors");
        assertRejected(FORMATION_SCHEMA, singular, "old singular sourceAnchor");

        ObjectNode empty = mutableFixture("result.formation.create.valid");
        ((ObjectNode) empty.at("/writeSet/0")).put("type", "EVENT");
        empty.withArray("writeSet").get(0).withArray("sourceAnchors").removeAll();
        assertRejected(FORMATION_SCHEMA, empty, "EVENT with empty sourceAnchors");

        ObjectNode missingVersion = mutableFixture("result.formation.create.valid");
        ((ObjectNode) missingVersion.at("/writeSet/0/sourceAnchors/0")).remove("sourceVersion");
        assertRejected(FORMATION_SCHEMA, missingVersion, "source anchor missing sourceVersion");

        ObjectNode memoryRevisionMasquerade = mutableFixture("result.formation.create.valid");
        ((ObjectNode) memoryRevisionMasquerade.at("/writeSet/0/sourceAnchors/0"))
                .put("sourceVersion", "revision:memory-version");
        assertRejected(FORMATION_SCHEMA, memoryRevisionMasquerade, "memory revision as source version");

        ObjectNode missingLocator = mutableFixture("result.formation.create.valid");
        ((ObjectNode) missingLocator.at("/writeSet/0/sourceAnchors/0")).remove("locator");
        assertRejected(FORMATION_SCHEMA, missingLocator, "source anchor missing locator");
    }

    @Test
    void formationBatchRefsAndActionShapesAreExpressibleInEitherOrder() throws Exception {
        JsonNode eventFirst = fixture("result.formation.batch.event.first.valid");
        JsonNode understandingFirst = fixture("result.formation.batch.understanding.first.valid");
        assertEquals("item:event-1", eventFirst.at("/writeSet/1/supporting/0").asText());
        assertEquals(
                "item:event-1",
                understandingFirst.at("/writeSet/0/supporting/0").asText());
        assertEquals(0, eventFirst.at("/writeSet/1/sourceAnchors").size());

        JsonNode mixed = fixture("result.formation.mixed.actions.valid");
        assertEquals(
                List.of("CREATE", "REVISE", "SUPERSEDE"),
                List.of(
                        mixed.at("/writeSet/0/action").asText(),
                        mixed.at("/writeSet/1/action").asText(),
                        mixed.at("/writeSet/2/action").asText()));
        for (JsonNode item : mixed.path("writeSet"))
            assertTrue(item.path("itemRef").asText().startsWith("item:"));
        assertFalse(mixed.at("/writeSet/0").has("expectedCurrent"));
        assertTrue(mixed.at("/writeSet/1").has("expectedCurrent"));
        assertTrue(mixed.at("/writeSet/2").has("expectedCurrent"));
        assertEquals("revision:source-2", mixed.at("/writeSet/1/supporting/0").asText());
        assertEquals("item:event-1", mixed.at("/writeSet/1/supporting/1").asText());
        assertEquals("item:event-1", mixed.at("/writeSet/2/counterExamples/1").asText());
    }

    @Test
    void formationGraphFactsAreRuntimeSemanticValidationNotApplicableToSchema() throws Exception {
        // RUNTIME_SEMANTIC_VALIDATION_NOT_APPLICABLE_TO_SCHEMA: these deliberately remain shape-valid.
        // S03-B2 Core must reject unknown targets, self-reference, cross-item cycles and SUPPORT+COUNTER overlap.
        for (String[] pair : List.of(
                new String[] {"result.formation.create.valid", "item:missing"},
                new String[] {"result.formation.create.valid", "item:create-1"})) {
            ObjectNode result = mutableFixture(pair[0]);
            result.withArray("writeSet").get(0).withArray("supporting").add(pair[1]);
            assertTrue(validateWorkerSchema(FORMATION_SCHEMA, result).isEmpty());
        }
        ObjectNode overlap = mutableFixture("result.formation.batch.event.first.valid");
        overlap.withArray("writeSet").get(1).withArray("counterExamples").add("item:event-1");
        assertTrue(validateWorkerSchema(FORMATION_SCHEMA, overlap).isEmpty());
        ObjectNode cycle = mutableFixture("result.formation.batch.event.first.valid");
        cycle.withArray("writeSet").get(0).withArray("supporting").add("item:understanding-1");
        assertTrue(validateWorkerSchema(FORMATION_SCHEMA, cycle).isEmpty());
    }

    @Test
    void r2TaskStartsWithCurrentTurnOrBoundedSourceWorkAndSeparatesExclusions() throws Exception {
        JsonNode retrieval = fixture("task.retrieval.sufficient.valid");
        assertTrue(retrieval.at("/contextMaterials/0/text").asText().startsWith("Synthetic turn:"));
        assertEquals(
                "CURRENT_USER_TURN", retrieval.at("/contextMaterials/0/kind").asText());
        assertEquals(
                "source:fixture-1", retrieval.at("/sourceExclusion/0/sourceRef").asText());
        assertEquals("revision:old", retrieval.at("/deliveredRevisionRefs/0").asText());
        JsonNode formation = fixture("task.formation.valid");
        assertEquals("source-work:fixture-1", formation.path("sourceWorkRef").asText());
        assertFalse(formation.has("sessionRef"), "Formation source binding must not require a chat session");

        ObjectNode oldExclusion = mutableFixture("task.retrieval.sufficient.valid");
        oldExclusion.withArray("sourceExclusion").set(0, "revision:old");
        assertRejected(TASK_SCHEMA, oldExclusion, "revision ref in source exclusion");

        ObjectNode duplicateDelivered = mutableFixture("task.retrieval.sufficient.valid");
        duplicateDelivered.withArray("deliveredRevisionRefs").add("revision:old");
        assertRejected(TASK_SCHEMA, duplicateDelivered, "duplicate delivered revision");

        ObjectNode missingTurn = mutableFixture("task.retrieval.sufficient.valid");
        missingTurn.remove("targetTurnRef");
        assertRejected(TASK_SCHEMA, missingTurn, "retrieval missing target turn");
        ObjectNode missingInputVersion = mutableFixture("task.retrieval.sufficient.valid");
        missingInputVersion.remove("inputVersion");
        assertRejected(TASK_SCHEMA, missingInputVersion, "retrieval missing input version");
        ObjectNode missingCurrentText = mutableFixture("task.retrieval.sufficient.valid");
        ((ObjectNode) missingCurrentText.at("/contextMaterials/0")).remove("text");
        assertRejected(TASK_SCHEMA, missingCurrentText, "current user turn without actual text");
        ObjectNode formationWithoutReadSource = mutableFixture("task.formation.valid");
        formationWithoutReadSource.withArray("allowedCapabilities").remove(0);
        assertRejected(TASK_SCHEMA, formationWithoutReadSource, "source work without READ_SOURCE capability");
    }

    @Test
    void r2TaskResultPairsRejectStaleGenerationsAndWrongRetrievalTargets() throws Exception {
        assertPair(fixture("task.retrieval.no-query.valid"), fixture("result.retrieval.no-query.valid"));
        assertPair(fixture("task.retrieval.sufficient.valid"), fixture("result.retrieval.sufficient.valid"));
        assertPair(fixture("task.formation.valid"), fixture("result.formation.create.valid"));
        assertPair(fixture("task.index.create-task.valid"), fixture("result.index.create-applied.valid"));

        for (String[] pair : List.of(
                new String[] {"task.retrieval.sufficient.valid", "result.retrieval.sufficient.valid", RETRIEVAL_SCHEMA},
                new String[] {"task.formation.valid", "result.formation.create.valid", FORMATION_SCHEMA},
                new String[] {"task.index.create-task.valid", "result.index.create-applied.valid", INDEX_SCHEMA})) {
            JsonNode task = fixture(pair[0]);
            ObjectNode stale = mutableFixture(pair[1]);
            stale.put("generation", task.path("generation").asInt() + 1);
            assertTrue(validateWorkerSchema(pair[2], stale).isEmpty(), "stale result remains individually well formed");
            assertThrows(AssertionError.class, () -> assertPair(task, stale), "wrong generation must be rejected");
            ObjectNode missingGeneration = mutableFixture(pair[1]);
            missingGeneration.remove("generation");
            assertRejected(pair[2], missingGeneration, "result missing lease generation");
        }
        JsonNode retrievalTask = fixture("task.retrieval.sufficient.valid");
        ObjectNode wrongTurn = mutableFixture("result.retrieval.sufficient.valid");
        wrongTurn.put("targetTurnRef", "turn:other");
        assertThrows(AssertionError.class, () -> assertPair(retrievalTask, wrongTurn));
        ObjectNode wrongInput = mutableFixture("result.retrieval.sufficient.valid");
        wrongInput.put("inputVersion", "input-other");
        assertThrows(AssertionError.class, () -> assertPair(retrievalTask, wrongInput));
    }

    @Test
    void indexProjectionPairsBindOneCommittedEventAndOneProjectionGeneration() throws Exception {
        for (String name : List.of("create", "revise", "supersede")) {
            JsonNode task = fixture("task.index." + name + "-task.valid");
            JsonNode applied = fixture("result.index." + name + "-applied.valid");
            assertTrue(validateWorkerSchema(TASK_SCHEMA, task).isEmpty());
            assertTrue(validateWorkerSchema(INDEX_SCHEMA, applied).isEmpty());
            assertPair(task, applied);
        }
        JsonNode task = fixture("task.index.create-task.valid");
        for (String id : List.of("result.index.retry.valid", "result.index.failed.valid")) {
            JsonNode result = fixture(id);
            assertTrue(validateWorkerSchema(INDEX_SCHEMA, result).isEmpty());
            assertPair(task, result);
            assertFalse(result.has("appliedRevisionRef"), id + " must not claim a durable write");
        }

        ObjectNode applied = mutableFixture("result.index.create-applied.valid");
        for (String field : List.of("taskRef", "worldRef", "eventRef", "materialDigest")) {
            ObjectNode wrong = applied.deepCopy();
            wrong.put(
                    field,
                    field.equals("materialDigest")
                            ? "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                            : field.equals("eventRef")
                                    ? "event:40404040-4040-4040-8040-404040404040"
                                    : field.equals("worldRef") ? "world:other" : "task:other");
            assertTrue(validateWorkerSchema(INDEX_SCHEMA, wrong).isEmpty(), field + " stays shape-valid");
            assertThrows(AssertionError.class, () -> assertPair(task, wrong), field + " must match the task");
        }
        for (String field : List.of("projectionGeneration", "generation")) {
            ObjectNode wrong = applied.deepCopy();
            wrong.put(field, applied.path(field).asInt() + 1);
            assertTrue(validateWorkerSchema(INDEX_SCHEMA, wrong).isEmpty());
            assertThrows(AssertionError.class, () -> assertPair(task, wrong), field + " must match the task");
        }
        for (String manifest : List.of("schemaManifest", "embeddingManifest")) {
            ObjectNode wrong = applied.deepCopy();
            ((ObjectNode) wrong.path(manifest)).put("version", "other");
            assertTrue(validateWorkerSchema(INDEX_SCHEMA, wrong).isEmpty());
            assertThrows(AssertionError.class, () -> assertPair(task, wrong), manifest + " must match the task");
        }
        ObjectNode wrongRevision = applied.deepCopy();
        wrongRevision.put("appliedRevisionRef", "revision:dddddddd-dddd-4ddd-8ddd-dddddddddddd");
        assertTrue(validateWorkerSchema(INDEX_SCHEMA, wrongRevision).isEmpty());
        assertThrows(AssertionError.class, () -> assertPair(task, wrongRevision));

        ObjectNode alteredMaterial = mutableFixture("task.index.create-task.valid");
        ((ObjectNode) alteredMaterial.path("projectionMaterial")).put("content", "Different committed content");
        assertTrue(validateWorkerSchema(TASK_SCHEMA, alteredMaterial).isEmpty());
        assertThrows(AssertionError.class, () -> assertPair(alteredMaterial, applied));
    }

    @Test
    void indexProjectionRelationArraysAcceptCanonical65And100ButReject101() throws Exception {
        for (String[] location : List.of(
                new String[] {"task.index.create-task.valid", "result.index.create-applied.valid", "/projectionMaterial"
                },
                new String[] {
                    "task.index.revise-task.valid",
                    "result.index.revise-applied.valid",
                    "/projectionMaterial/relatedRevisionMaterials/0"
                })) {
            for (String field : List.of("supportingRevisionRefs", "counterRevisionRefs")) {
                for (int count : List.of(65, 100, 101)) {
                    ObjectNode task = mutableFixture(location[0]);
                    ObjectNode material = (ObjectNode) task.at(location[2]);
                    material.withArray(field).removeAll();
                    for (int i = 1; i <= count; i++) {
                        material.withArray(field).add(String.format("revision:%08x-0000-4000-8000-%012x", i, i));
                    }
                    task.put("materialDigest", projectionMaterialDigest(task.path("projectionMaterial")));
                    List<Error> errors = validateWorkerSchema(TASK_SCHEMA, task);
                    if (count <= 100) {
                        assertTrue(
                                errors.isEmpty(),
                                location[2] + "/" + field + " with " + count + " refs must be valid: " + errors);
                        ObjectNode result = mutableFixture(location[1]);
                        result.put("materialDigest", task.path("materialDigest").asText());
                        assertPair(task, result);
                    } else {
                        String path = location[2] + "/" + field;
                        assertTrue(
                                errors.stream()
                                        .anyMatch(error -> "maxItems".equals(error.getKeyword())
                                                && path.equals(String.valueOf(error.getInstanceLocation()))),
                                path + " with 101 refs must fail maxItems: " + errors);
                    }
                }
            }
        }
        // Per-array Schema limits cannot enforce Core's combined 100-relation invariant.
        ObjectNode combinedOverflow = mutableFixture("task.index.create-task.valid");
        ObjectNode primary = (ObjectNode) combinedOverflow.path("projectionMaterial");
        for (int i = 1; i <= 60; i++) {
            primary.withArray("supportingRevisionRefs").add(String.format("revision:%08x-0000-4000-8000-%012x", i, i));
        }
        for (int i = 61; i <= 101; i++) {
            primary.withArray("counterRevisionRefs").add(String.format("revision:%08x-0000-4000-8000-%012x", i, i));
        }
        combinedOverflow.put("materialDigest", projectionMaterialDigest(primary));
        assertTrue(
                validateWorkerSchema(TASK_SCHEMA, combinedOverflow).isEmpty(),
                "Core must reject a 60+41 relation total before issuing this shape-valid task");
    }

    @Test
    void r2ResultExitsRejectHalfProductsAndContradictoryHistory() throws Exception {
        ObjectNode failedRetrieval = mutableFixture("result.retrieval.failed.valid");
        failedRetrieval.set(
                "selected",
                fixture("result.retrieval.no-support.valid").path("selected").deepCopy());
        assertRejected(RETRIEVAL_SCHEMA, failedRetrieval, "failed retrieval with selected");
        ObjectNode failedCandidates = mutableFixture("result.retrieval.failed.valid");
        failedCandidates.set(
                "candidateRefs",
                fixture("result.retrieval.no-support.valid")
                        .path("candidateRefs")
                        .deepCopy());
        assertRejected(RETRIEVAL_SCHEMA, failedCandidates, "failed retrieval with candidateRefs");
        ObjectNode completedFailureCode = mutableFixture("result.retrieval.sufficient.valid");
        completedFailureCode.put("failureCode", "CORE_RESOLVE_FAILED");
        assertRejected(RETRIEVAL_SCHEMA, completedFailureCode, "completed retrieval with failure code");
        ObjectNode contradictoryHistory = mutableFixture("result.retrieval.sufficient.valid");
        ((ObjectNode) contradictoryHistory.at("/selected/0")).put("historical", true);
        assertRejected(RETRIEVAL_SCHEMA, contradictoryHistory, "current and historical both true");
        ObjectNode missingHistory = mutableFixture("result.retrieval.sufficient.valid");
        ((ObjectNode) missingHistory.at("/selected/0")).remove("historical");
        assertRejected(RETRIEVAL_SCHEMA, missingHistory, "missing historical flag");
        ObjectNode validHistorical = mutableFixture("result.retrieval.sufficient.valid");
        ((ObjectNode) validHistorical.at("/selected/0")).put("current", false).put("historical", true);
        assertTrue(validateWorkerSchema(RETRIEVAL_SCHEMA, validHistorical).isEmpty());

        ObjectNode writeWithFailure = mutableFixture("result.formation.create.valid");
        writeWithFailure.put("failureCode", "FORMATION_FAILED");
        assertRejected(FORMATION_SCHEMA, writeWithFailure, "WRITE_SET with failure code");
        ObjectNode sourceIncompleteWithWrite = mutableFixture("result.formation.source-incomplete.valid");
        sourceIncompleteWithWrite.set(
                "writeSet",
                fixture("result.formation.create.valid").path("writeSet").deepCopy());
        assertRejected(FORMATION_SCHEMA, sourceIncompleteWithWrite, "SOURCE_INCOMPLETE with writeSet");
        ObjectNode budgetWithWrite = mutableFixture("result.formation.budget-exhausted.valid");
        budgetWithWrite.set(
                "writeSet",
                fixture("result.formation.create.valid").path("writeSet").deepCopy());
        assertRejected(FORMATION_SCHEMA, budgetWithWrite, "BUDGET_EXHAUSTED with writeSet");
        ObjectNode wrongFailureStatus = mutableFixture("result.formation.source-incomplete.valid");
        wrongFailureStatus.put("resultType", "FAILED").put("failureCode", "FORMATION_FAILED");
        assertRejected(FORMATION_SCHEMA, wrongFailureStatus, "FAILED with COMPLETED executionStatus");
        ObjectNode wrongBudgetStatus = mutableFixture("result.formation.budget-exhausted.valid");
        wrongBudgetStatus.put("executionStatus", "FAILED");
        assertRejected(FORMATION_SCHEMA, wrongBudgetStatus, "BUDGET_EXHAUSTED with FAILED executionStatus");

        for (String[] exit : List.of(
                new String[] {"FAILED", "FORMATION_FAILED"},
                new String[] {"TIMED_OUT", "TIMEOUT"},
                new String[] {"CANCELLED", "CANCELLED"},
                new String[] {"UNAVAILABLE", "UNAVAILABLE"})) {
            ObjectNode failedFormation = mutableFixture("result.formation.failed.valid");
            failedFormation.put("executionStatus", exit[0]).put("failureCode", exit[1]);
            assertTrue(
                    validateWorkerSchema(FORMATION_SCHEMA, failedFormation).isEmpty(),
                    "valid Formation failure exit " + exit[0]);
        }
        for (String[] exit : List.of(
                new String[] {"FAILED", "CORE_RESOLVE_FAILED"},
                new String[] {"TIMED_OUT", "TIMEOUT"},
                new String[] {"BUDGET_EXHAUSTED", "BUDGET_EXHAUSTED"},
                new String[] {"CANCELLED", "CANCELLED"},
                new String[] {"UNAVAILABLE", "INDEX_UNAVAILABLE"})) {
            ObjectNode failedResult = mutableFixture("result.retrieval.failed.valid");
            failedResult.put("executionStatus", exit[0]).put("failureCode", exit[1]);
            assertTrue(
                    validateWorkerSchema(RETRIEVAL_SCHEMA, failedResult).isEmpty(),
                    "valid Retrieval failure exit " + exit[0]);
        }
    }

    private static ObjectNode mutableFixture(String id) throws IOException {
        return (ObjectNode) fixture(id).deepCopy();
    }

    private static void assertRejected(String schemaRef, JsonNode instance, String label) throws IOException {
        assertFalse(validateWorkerSchema(schemaRef, instance).isEmpty(), label + " unexpectedly passed validation");
    }

    private static void assertPair(JsonNode task, JsonNode result) throws Exception {
        for (String field : List.of("contractVersion", "taskRef", "worldRef", "taskKind", "generation")) {
            assertEquals(task.path(field), result.path(field), "task/result mismatch at " + field);
        }
        if ("RETRIEVAL".equals(task.path("taskKind").asText())) {
            for (String field : List.of("targetTurnRef", "inputVersion")) {
                assertEquals(task.path(field), result.path(field), "retrieval target mismatch at " + field);
            }
        }
        if ("INDEX_PROJECTION".equals(task.path("taskKind").asText())) {
            assertEquals(
                    projectionMaterialDigest(task.path("projectionMaterial")),
                    task.path("materialDigest").asText(),
                    "projection material digest mismatch");
            for (String field : List.of(
                    "eventRef", "projectionGeneration", "materialDigest", "schemaManifest", "embeddingManifest")) {
                assertEquals(task.path(field), result.path(field), "index projection mismatch at " + field);
            }
            if ("APPLIED".equals(result.path("outcome").asText())) {
                assertEquals(
                        task.at("/projectionMaterial/revisionRef"),
                        result.path("appliedRevisionRef"),
                        "APPLIED must identify this event's committed revision");
            }
        }
    }

    private static String projectionMaterialDigest(JsonNode material) throws Exception {
        byte[] canonical = ContractTestSupport.JSON.writeValueAsBytes(canonicalValue(material));
        return "sha256:"
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    }

    private static Object canonicalValue(JsonNode node) {
        if (node.isObject()) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), canonicalValue(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            node.forEach(value -> values.add(canonicalValue(value)));
            return values;
        }
        if (node.isNull()) return null;
        if (node.isIntegralNumber()) return node.asLong();
        return node.asText();
    }

    private static JsonNode fixture(String id) throws IOException {
        for (JsonNode entry : ContractTestSupport.loadJson(MANIFEST).path("fixtures")) {
            if (id.equals(entry.path("id").asText())) {
                return ContractTestSupport.loadJson(
                        ContractTestSupport.REPO_ROOT.resolve(ContractTestSupport.text(entry, "fixturePath")));
            }
        }
        fail("missing worker fixture: " + id);
        return null;
    }

    private static List<Error> validateWorkerSchema(String schemaRef, JsonNode instance) throws IOException {
        JsonNode schemaNode = ContractTestSupport.loadJson(ContractTestSupport.REPO_ROOT.resolve(schemaRef));
        SchemaRegistryConfig config =
                SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build();
        Schema schema = SchemaRegistry.withDefaultDialect(
                        SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaRegistryConfig(config))
                .getSchema(schemaNode);
        return schema.validate(instance);
    }

    private static boolean hasCrossWorldReference(JsonNode result) {
        String world = result.path("worldRef").asText();
        for (JsonNode candidate : result.path("candidateRefs")) {
            if (!world.equals(candidate.path("worldRef").asText())) return true;
        }
        return false;
    }

    private static void assertEqualsPathSet(Set<String> manifestPaths) throws IOException {
        Set<String> actual = new HashSet<>();
        for (String kind : List.of("valid", "invalid")) {
            try (Stream<Path> files = Files.walk(WORKER_ROOT.resolve("fixtures").resolve(kind))) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .forEach(path -> actual.add(ContractTestSupport.REPO_ROOT
                                .relativize(path)
                                .toString()
                                .replace('\\', '/')));
            }
        }
        assertTrue(manifestPaths.equals(actual), "manifest/fixture path set mismatch");
    }
}
