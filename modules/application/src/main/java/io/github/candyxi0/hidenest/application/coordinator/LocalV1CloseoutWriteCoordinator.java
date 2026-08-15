package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.CanonicalFailureCode;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutReceipt;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.AnchorUnit;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.EvidenceMessage;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.SourceAnchor;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.ThreadReaderManifest;
import io.github.candyxi0.hidenest.application.model.LocalV1RunStatus;
import io.github.candyxi0.hidenest.application.model.LocalV1S1ConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1PrepareRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1PrepareResult;
import io.github.candyxi0.hidenest.runtime.domain.CaptureScope;
import io.github.candyxi0.hidenest.runtime.domain.CaptureScopeUnit;
import io.github.candyxi0.hidenest.runtime.domain.CloseoutRun;
import io.github.candyxi0.hidenest.runtime.domain.IdempotencyReceipt;
import io.github.candyxi0.hidenest.runtime.port.RuntimeQueryPort;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Local V1 closeout write facade.
 *
 * <p>This facade only does transport mapping, deterministic identity, phase orchestration and
 * recovery. It does not copy any of the business writes inside
 * {@link LocalV1S1WindowCloseCoordinator#prepare} or {@link LocalV1S1WindowCloseCoordinator#confirm};
 * it reuses both. Each phase commits independently so a crash between phases is recoverable: a
 * replay with the same submissionId + request hash resumes from the facts already persisted and
 * converges to the same {@code runId}/{@code memoryId} without a second memory, source or
 * duplicate evidence.</p>
 */
public class LocalV1CloseoutWriteCoordinator {

    private static final int MAX_BODY_CODE_POINTS = 16000;
    private static final int MAX_EVIDENCE_BYTES = 1024 * 1024; // 1 MiB UTF-8
    private static final int MAX_UNITS_PER_ANCHOR = 100;
    private static final int MAX_EVIDENCE_MESSAGES = 100;
    private static final String SCHEMA_VERSION = "local-v1-synthetic-v1";
    private static final String DECISION_CONFIRM = "CONFIRM";
    private static final String RULE_VERSION = "LOCAL_V1_SYNTHETIC";
    private static final String COVERAGE_CODE = "SYNTHETIC_SELECTED";
    private static final String OPERATION_CODE = "LOCAL_V1_CLOSEOUT";
    private static final Set<String> VALID_MEMORY_TYPES =
            Set.of("EVENT", "CLAIM", "QUOTE", "INTERPRETATION", "CALIBRATION", "PRINCIPLE");

    private final RuntimeTransactionPort runtimePort;
    private final RuntimeQueryPort runtimeQueryPort;
    private final TransactionExecutor tx;
    private final LocalV1S1WindowCloseCoordinator s1;
    private final Clock clock;

    public LocalV1CloseoutWriteCoordinator(
            RuntimeTransactionPort runtimePort,
            RuntimeQueryPort runtimeQueryPort,
            TransactionExecutor tx,
            LocalV1S1WindowCloseCoordinator s1,
            Clock clock) {
        this.runtimePort = runtimePort;
        this.runtimeQueryPort = runtimeQueryPort;
        this.tx = tx;
        this.s1 = s1;
        this.clock = clock;
    }

    // ── submit ────────────────────────────────────────────────────────────

    public LocalV1CloseoutReceipt submit(LocalV1CloseoutSubmission request) {
        validate(request);
        byte[] requestHash = computeRequestHash(request);
        UUID submissionId = request.submissionId();
        UUID runId = submissionId; // Local V1: runId == submissionId (unique binding)
        UUID memoryId = deterministicId("memory:", submissionId);
        UUID policyId = deterministicId("policy:", submissionId);
        UUID scopeId = deterministicId("scope:", submissionId);
        String prepareKey = "local-v1-prepare:" + submissionId;
        String confirmKey = "local-v1-confirm:" + submissionId;
        LocalV1S1PrepareRequest prepareRequest = buildPrepareRequest(request, prepareKey, requestHash);

        Gate gate = tx.executeInTransaction(() -> {
            runtimePort.lockIdempotencyKey(submissionId.toString());
            IdempotencyReceipt existing = runtimePort.findReceiptByKey(submissionId.toString());
            if (existing == null) {
                return Gate.PROCEED;
            }
            if (!Arrays.equals(existing.requestHash(), requestHash)) {
                throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.IDEMPOTENCY_KEY_REUSED);
            }
            return Gate.REPLAY;
        });
        if (gate == Gate.REPLAY) {
            return replayVerify(request, requestHash, runId, memoryId, scopeId, prepareRequest,
                    confirmKey, policyId);
        }

        // Phase 1: prepare (idempotent under prepareKey; S1 owns its own lock).
        LocalV1S1PrepareResult prepared;
        try {
            prepared = s1.prepare(prepareRequest);
        } catch (RuntimeException exception) {
            throw new LocalV1CloseoutException(failureCodeOf(exception), exception);
        }

        // Phase 2: locked create-if-absent for CaptureScope + CloseoutRun.
        tx.executeInTransaction(() -> {
            runtimePort.lockIdempotencyKey(submissionId.toString());
            if (runtimeQueryPort.findCloseoutRunBySubmissionId(submissionId) != null) {
                return null;
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            runtimePort.insertCaptureScope(new CaptureScope(
                    scopeId,
                    prepared.sourceId(),
                    request.threadReaderManifest().fromOrdinal(),
                    request.threadReaderManifest().toOrdinal(),
                    RULE_VERSION,
                    COVERAGE_CODE,
                    null,
                    hexToBytes(request.threadReaderManifest().manifestHash()),
                    now));
            List<CaptureScopeUnit> units = new ArrayList<>();
            for (EvidenceMessage message : request.threadReaderManifest().selectedEvidenceMessages()) {
                units.add(new CaptureScopeUnit(scopeId, message.sourceUnitId(), message.ordinal(), null));
            }
            runtimePort.insertCaptureScopeUnits(units);
            runtimePort.freezeCaptureScope(scopeId, now);
            runtimePort.insertCloseoutRun(new CloseoutRun(
                    runId, scopeId, "READY", null, submissionId, null, null, null, now));
            return null;
        });

        // Phase 3: confirm (idempotent under confirmKey; S1 owns its own lock).
        byte[] manifestHash = hexToBytes(request.userConfirmation().reviewManifestHash());
        try {
            s1.confirm(new LocalV1S1ConfirmRequest(
                    confirmKey,
                    requestHash,
                    prepared.proposalRevisionId(),
                    prepared.reviewSessionId(),
                    memoryId,
                    policyId,
                    manifestHash));
        } catch (RuntimeException exception) {
            throw new LocalV1CloseoutException(failureCodeOf(exception), exception);
        }

        // Phase 4: locked run state convergence.
        tx.executeInTransaction(() -> {
            runtimePort.lockIdempotencyKey(submissionId.toString());
            CloseoutRun run = runtimeQueryPort.findCloseoutRunBySubmissionId(submissionId);
            if (run == null) {
                throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.CANONICAL_COMMIT_FAILED);
            }
            convergeToCompleted(run, OffsetDateTime.now(clock));
            return null;
        });

        // Phase 5: locked facade receipt creation.
        tx.executeInTransaction(() -> {
            runtimePort.lockIdempotencyKey(submissionId.toString());
            if (runtimePort.findReceiptByKey(submissionId.toString()) == null) {
                runtimePort.commitReceipt(
                        submissionId.toString(), OPERATION_CODE, requestHash, memoryId, "MEMORY",
                        buildReceiptManifest(memoryId));
            }
            return null;
        });

        return LocalV1CloseoutReceipt.of(runId, memoryId, "CANONICAL_COMMITTED");
    }

    // ── run status ────────────────────────────────────────────────────────

    public LocalV1RunStatus findRunStatus(UUID runId) {
        CloseoutRun run = runtimeQueryPort.findCloseoutRunById(runId);
        if (run == null) {
            return null;
        }
        return new LocalV1RunStatus(
                run.runId(),
                projectPhase(run.state()),
                run.failureCode(),
                run.startedAt(),
                run.terminalAt(),
                false);
    }

    // ── recovery ──────────────────────────────────────────────────────────

    private LocalV1CloseoutReceipt replayVerify(
            LocalV1CloseoutSubmission request,
            byte[] requestHash,
            UUID runId,
            UUID memoryId,
            UUID scopeId,
            LocalV1S1PrepareRequest prepareRequest,
            String confirmKey,
            UUID policyId) {
        UUID submissionId = request.submissionId();

        // 1. Facade receipt must match exactly (locked).
        tx.executeInTransaction(() -> {
            runtimePort.lockIdempotencyKey(submissionId.toString());
            IdempotencyReceipt receipt = runtimePort.findReceiptByKey(submissionId.toString());
            if (receipt == null
                    || !OPERATION_CODE.equals(receipt.operationCode())
                    || !Arrays.equals(receipt.requestHash(), requestHash)
                    || !"MEMORY".equals(receipt.resourceKind())
                    || !memoryId.equals(receipt.resourceId())) {
                throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.CANONICAL_COMMIT_FAILED);
            }
            return null;
        });

        // 2. Reuse s1.prepare replay path (Review/Proposal/Source/anchors/hide Decision).
        LocalV1S1PrepareResult prepared;
        try {
            prepared = s1.prepare(prepareRequest);
        } catch (RuntimeException exception) {
            throw new LocalV1CloseoutException(failureCodeOf(exception), exception);
        }

        // 3. Reuse s1.confirm replay path (Memory/current Revision/USER_CONFIRM/EVIDENCED_BY).
        byte[] manifestHash = hexToBytes(request.userConfirmation().reviewManifestHash());
        try {
            s1.confirm(new LocalV1S1ConfirmRequest(
                    confirmKey,
                    requestHash,
                    prepared.proposalRevisionId(),
                    prepared.reviewSessionId(),
                    memoryId,
                    policyId,
                    manifestHash));
        } catch (RuntimeException exception) {
            throw new LocalV1CloseoutException(failureCodeOf(exception), exception);
        }

        // 4. Verify the real run, scope and units (locked).
        tx.executeInTransaction(() -> {
            runtimePort.lockIdempotencyKey(submissionId.toString());
            verifyRunScopeUnits(request, runId, scopeId, prepared.sourceId());
            return null;
        });

        return LocalV1CloseoutReceipt.of(runId, memoryId, "CANONICAL_COMMITTED");
    }

    private void verifyRunScopeUnits(
            LocalV1CloseoutSubmission request, UUID runId, UUID scopeId, UUID expectedSourceId) {
        CloseoutRun run = runtimeQueryPort.findCloseoutRunById(runId);
        if (run == null
                || !"COMPLETED".equals(run.state())
                || !scopeId.equals(run.scopeId())
                || !request.submissionId().equals(run.submissionId())) {
            throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.CANONICAL_COMMIT_FAILED);
        }

        CaptureScope scope = runtimeQueryPort.findCaptureScopeById(scopeId);
        if (scope == null
                || scope.frozenAt() == null
                || !expectedSourceId.equals(scope.sourceId())
                || !request.threadReaderManifest().fromOrdinal().equals(scope.fromOrdinal())
                || !request.threadReaderManifest().toOrdinal().equals(scope.toOrdinal())
                || !RULE_VERSION.equals(scope.ruleVersion())
                || !COVERAGE_CODE.equals(scope.coverageCode())
                || !Arrays.equals(
                        hexToBytes(request.threadReaderManifest().manifestHash()), scope.manifestHash())) {
            throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.CANONICAL_COMMIT_FAILED);
        }

        List<CaptureScopeUnit> units = runtimeQueryPort.findCaptureScopeUnitsByScopeId(scopeId);
        Set<UnitKey> expected = new HashSet<>();
        for (EvidenceMessage message : request.threadReaderManifest().selectedEvidenceMessages()) {
            expected.add(new UnitKey(message.sourceUnitId(), message.ordinal()));
        }
        Set<UnitKey> actual = new HashSet<>();
        for (CaptureScopeUnit unit : units) {
            if (!actual.add(new UnitKey(unit.sourceUnitId(), unit.ordinal()))) {
                throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.CANONICAL_COMMIT_FAILED);
            }
        }
        if (units.size() != expected.size() || !actual.equals(expected)) {
            throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.CANONICAL_COMMIT_FAILED);
        }
    }

    private record UnitKey(UUID sourceUnitId, Long ordinal) {}

    private void convergeToCompleted(CloseoutRun run, OffsetDateTime now) {
        String state = run.state();
        OffsetDateTime startedAt = run.startedAt() != null ? run.startedAt() : now;
        if ("READY".equals(state)) {
            if (!runtimePort.transitionCloseoutRun(run.runId(), "READY", "RUNNING", startedAt, null, null)
                    || !runtimePort.transitionCloseoutRun(
                            run.runId(), "RUNNING", "COMPLETED", startedAt, now, null)) {
                throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.CANONICAL_COMMIT_FAILED);
            }
        } else if ("RUNNING".equals(state)) {
            if (!runtimePort.transitionCloseoutRun(
                    run.runId(), "RUNNING", "COMPLETED", startedAt, now, null)) {
                throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.CANONICAL_COMMIT_FAILED);
            }
        } else if (!"COMPLETED".equals(state)) {
            throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.CANONICAL_COMMIT_FAILED);
        }
    }

    private static String projectPhase(String state) {
        return switch (state) {
            case "COMPLETED" -> "CANONICAL_COMMITTED";
            case "FAILED", "CANCELLED" -> "FINAL_FAILED";
            default -> "RECEIVED"; // READY / RUNNING / unknown
        };
    }

    // ── mapping ───────────────────────────────────────────────────────────

    private LocalV1S1PrepareRequest buildPrepareRequest(
            LocalV1CloseoutSubmission request, String prepareKey, byte[] requestHash) {
        List<LocalV1S1PrepareRequest.EvidenceMessage> messages =
                request.threadReaderManifest().selectedEvidenceMessages().stream()
                        .map(message -> new LocalV1S1PrepareRequest.EvidenceMessage(
                                message.sourceUnitId(),
                                message.actorId(),
                                message.ordinal(),
                                message.externalUnitRef(),
                                message.occurredAt(),
                                message.bodyText()))
                        .toList();
        List<LocalV1S1PrepareRequest.AnchorInput> anchors = request.sourceAnchors().stream()
                .map(anchor -> new LocalV1S1PrepareRequest.AnchorInput(
                        anchor.anchorId(),
                        anchor.units().stream()
                                .map(unit -> new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(
                                        unit.sourceUnitId(),
                                        unit.fromOffset(),
                                        unit.toOffset(),
                                        unit.ordinal()))
                                .toList()))
                .toList();
        return new LocalV1S1PrepareRequest(
                prepareKey,
                requestHash,
                request.hideSelection().perspectiveActorId(),
                toS1MemoryType(request.hideSelection().memoryType()),
                request.hideSelection().bodyText(),
                hexToBytes(request.hideSelection().bodyHash()),
                messages,
                anchors);
    }

    /** Wire MemoryType enum value (uppercase) → S1 canonical value (PascalCase). */
    private static String toS1MemoryType(String wire) {
        return switch (wire) {
            case "EVENT" -> "Event";
            case "CLAIM" -> "Claim";
            case "QUOTE" -> "Quote";
            case "INTERPRETATION" -> "Interpretation";
            case "CALIBRATION" -> "Calibration";
            case "PRINCIPLE" -> "Principle";
            default -> throw schema();
        };
    }

    // ── validation ────────────────────────────────────────────────────────

    private void validate(LocalV1CloseoutSubmission request) {
        if (request == null
                || request.submissionId() == null
                || request.threadId() == null
                || request.hideSelection() == null
                || request.userConfirmation() == null
                || request.threadReaderManifest() == null) {
            throw schema();
        }

        validateHideSelection(request.hideSelection());
        validateUserConfirmation(request.userConfirmation());

        ThreadReaderManifest manifest = request.threadReaderManifest();
        if (!SCHEMA_VERSION.equals(manifest.schemaVersion())
                || manifest.fromOrdinal() == null
                || manifest.toOrdinal() == null
                || manifest.fromOrdinal() < 0
                || manifest.toOrdinal() < manifest.fromOrdinal()
                || !is64Hex(manifest.manifestHash())
                || manifest.selectedEvidenceMessages() == null
                || manifest.selectedEvidenceMessages().isEmpty()
                || manifest.selectedEvidenceMessages().size() > MAX_EVIDENCE_MESSAGES) {
            throw schema();
        }

        Set<UUID> selectedUnits = new HashSet<>();
        Set<UUID> selectedActors = new HashSet<>();
        Set<Long> selectedOrdinals = new HashSet<>();
        Map<UUID, Long> ordinalByUnit = new HashMap<>();
        Long previousOrdinal = null;
        for (EvidenceMessage message : manifest.selectedEvidenceMessages()) {
            if (message.sourceUnitId() == null || message.actorId() == null) {
                throw schema();
            }
            if (!selectedUnits.add(message.sourceUnitId())) {
                throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.SOURCE_ORDER_INVALID);
            }
            selectedActors.add(message.actorId());
            if (message.ordinal() == null || message.ordinal() < 0) {
                throw schema();
            }
            if (!selectedOrdinals.add(message.ordinal())) {
                throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.SOURCE_ORDER_INVALID);
            }
            if (message.ordinal() < manifest.fromOrdinal() || message.ordinal() > manifest.toOrdinal()) {
                throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.SOURCE_RANGE_GAP);
            }
            // R4-R1: request-order ordinals must be strictly increasing (no duplicates, no descending).
            if (previousOrdinal != null && message.ordinal() <= previousOrdinal) {
                throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.SOURCE_ORDER_INVALID);
            }
            previousOrdinal = message.ordinal();
            ordinalByUnit.put(message.sourceUnitId(), message.ordinal());
            if (message.externalUnitRef() == null
                    || message.externalUnitRef().isEmpty()
                    || message.externalUnitRef().length() > 256
                    || message.occurredAt() == null
                    || message.bodyText() == null
                    || message.bodyText().isEmpty()
                    || message.bodyText().getBytes(StandardCharsets.UTF_8).length > MAX_EVIDENCE_BYTES
                    || !is64Hex(message.bodyHash())) {
                throw schema();
            }
            if (!constantTimeEquals(sha256Hex(message.bodyText()), message.bodyHash())) {
                throw schema();
            }
        }

        // R4-R1: fromOrdinal/toOrdinal must equal the true first/last selected ordinal.
        EvidenceMessage firstMessage = manifest.selectedEvidenceMessages().get(0);
        EvidenceMessage lastMessage =
                manifest.selectedEvidenceMessages().get(manifest.selectedEvidenceMessages().size() - 1);
        if (!firstMessage.ordinal().equals(manifest.fromOrdinal())
                || !lastMessage.ordinal().equals(manifest.toOrdinal())) {
            throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.SOURCE_RANGE_GAP);
        }

        // Thread reader manifest hash must bind the actual selected evidence messages.
        if (!constantTimeEquals(
                LocalV1CloseoutCanonicalizer.threadManifestHash(manifest), manifest.manifestHash())) {
            throw schema();
        }

        // The perspective actor (小林) must be one of the selected evidence actors; the
        // proposal_revision perspective_actor_id is a foreign key into memory.actor_ref.
        if (!selectedActors.contains(request.hideSelection().perspectiveActorId())) {
            throw schema();
        }

        validateAnchors(request.sourceAnchors(), selectedUnits, ordinalByUnit);

        // R4-R1: continuous must be exactly "single anchor".
        if (manifest.continuous() != (request.sourceAnchors().size() == 1)) {
            throw schema();
        }

        // The confirmation source unit must never be mixed into selected evidence.
        if (selectedUnits.contains(request.userConfirmation().confirmationSourceUnitId())) {
            throw schema();
        }

        // Review manifest hash must bind the confirmed candidate, manifest and anchor graph.
        if (!constantTimeEquals(
                LocalV1CloseoutCanonicalizer.reviewManifestHash(request),
                request.userConfirmation().reviewManifestHash())) {
            throw schema();
        }

        if (!is64Hex(request.confirmationProof())) {
            throw schema();
        }
        String expectedProof = LocalV1CloseoutCanonicalizer.confirmationProof(
                request.threadId(),
                request.userConfirmation().confirmationSourceUnitId(),
                request.userConfirmation().reviewManifestHash(),
                request.submissionId());
        if (!constantTimeEquals(expectedProof, request.confirmationProof())) {
            throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.USER_CONFIRMATION_PROOF_INVALID);
        }
    }

    private void validateHideSelection(LocalV1CloseoutSubmission.HideSelection hideSelection) {
        if (hideSelection.perspectiveActorId() == null
                || hideSelection.memoryType() == null
                || !VALID_MEMORY_TYPES.contains(hideSelection.memoryType())
                || hideSelection.bodyText() == null
                || hideSelection.bodyText().isEmpty()
                || hideSelection.bodyText().codePointCount(0, hideSelection.bodyText().length())
                        > MAX_BODY_CODE_POINTS
                || !is64Hex(hideSelection.bodyHash())) {
            throw schema();
        }
        if (!constantTimeEquals(sha256Hex(hideSelection.bodyText()), hideSelection.bodyHash())) {
            throw schema();
        }
    }

    private void validateUserConfirmation(LocalV1CloseoutSubmission.UserConfirmation confirmation) {
        if (!DECISION_CONFIRM.equals(confirmation.decision())
                || confirmation.confirmationSourceUnitId() == null
                || !is64Hex(confirmation.reviewManifestHash())) {
            throw schema();
        }
    }

    private void validateAnchors(
            List<SourceAnchor> anchors, Set<UUID> selectedUnits, Map<UUID, Long> ordinalByUnit) {
        if (anchors == null || anchors.isEmpty()) {
            throw schema();
        }
        Set<UUID> anchorIds = new HashSet<>();
        Set<UUID> partitionedUnits = new HashSet<>();
        Long previousAnchorLastOrdinal = null;
        for (SourceAnchor anchor : anchors) {
            if (anchor.anchorId() == null || !anchorIds.add(anchor.anchorId())) {
                throw schema();
            }
            if (anchor.units() == null
                    || anchor.units().isEmpty()
                    || anchor.units().size() > MAX_UNITS_PER_ANCHOR) {
                throw schema();
            }
            Set<UUID> anchorUnits = new HashSet<>();
            Set<Long> anchorOrdinals = new HashSet<>();
            Long previousUnitOrdinal = null;
            Long anchorFirstOrdinal = null;
            Long anchorLastOrdinal = null;
            for (AnchorUnit unit : anchor.units()) {
                if (unit.sourceUnitId() == null || !anchorUnits.add(unit.sourceUnitId())) {
                    throw schema();
                }
                // R4-R1: each selected source unit is partitioned into exactly one anchor.
                if (!partitionedUnits.add(unit.sourceUnitId())) {
                    throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.SOURCE_ORDER_INVALID);
                }
                boolean fromNull = unit.fromOffset() == null;
                boolean toNull = unit.toOffset() == null;
                if (fromNull != toNull) {
                    throw schema();
                }
                if (!fromNull && (unit.fromOffset() < 0 || unit.toOffset() < unit.fromOffset())) {
                    throw schema();
                }
                if (unit.ordinal() == null || unit.ordinal() < 0) {
                    throw schema();
                }
                if (!anchorOrdinals.add(unit.ordinal())) {
                    throw schema(); // duplicate ordinal within one anchor
                }
                // R4-R1: an anchor unit ordinal must bind to the corresponding selected message ordinal.
                Long messageOrdinal = ordinalByUnit.get(unit.sourceUnitId());
                if (messageOrdinal == null || !unit.ordinal().equals(messageOrdinal)) {
                    throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.SOURCE_ORDER_INVALID);
                }
                // R4-R1: within an anchor ordinals are strictly increasing and contiguous.
                if (previousUnitOrdinal != null && unit.ordinal() != previousUnitOrdinal + 1) {
                    throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.SOURCE_ORDER_INVALID);
                }
                previousUnitOrdinal = unit.ordinal();
                if (anchorFirstOrdinal == null) {
                    anchorFirstOrdinal = unit.ordinal();
                }
                anchorLastOrdinal = unit.ordinal();
            }
            // R4-R1: anchors are first-ordinal ascending, non-overlapping and never adjacent.
            if (previousAnchorLastOrdinal != null) {
                if (anchorFirstOrdinal <= previousAnchorLastOrdinal) {
                    throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.SOURCE_ORDER_INVALID);
                }
                if (anchorFirstOrdinal == previousAnchorLastOrdinal + 1) {
                    throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.SOURCE_ORDER_INVALID);
                }
            }
            previousAnchorLastOrdinal = anchorLastOrdinal;
        }
        // R4-R1: every selected unit is partitioned (no missing, no extra).
        if (!partitionedUnits.equals(selectedUnits)) {
            throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.SOURCE_ORDER_INVALID);
        }
    }

    private static LocalV1CloseoutException schema() {
        return new LocalV1CloseoutException(LocalV1CloseoutException.Code.REQUEST_SCHEMA_INVALID);
    }

    // ── hashing / identity ────────────────────────────────────────────────

    /** Deterministic full-request hash used for idempotency and conflict detection. */
    private static byte[] computeRequestHash(LocalV1CloseoutSubmission request) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(request.submissionId()).append('\n');
        canonical.append(request.threadId()).append('\n');
        canonical.append(request.hideSelection().perspectiveActorId()).append('\n');
        canonical.append(request.hideSelection().memoryType()).append('\n');
        canonical.append(request.hideSelection().bodyText()).append('\n');
        canonical.append(request.hideSelection().bodyHash()).append('\n');
        canonical.append(request.userConfirmation().decision()).append('\n');
        canonical.append(request.userConfirmation().reviewManifestHash()).append('\n');
        canonical.append(request.userConfirmation().confirmationSourceUnitId()).append('\n');
        for (SourceAnchor anchor : request.sourceAnchors()) {
            canonical.append(anchor.anchorId()).append('\n');
            for (AnchorUnit unit : anchor.units()) {
                canonical.append(unit.sourceUnitId()).append('\n');
                canonical.append(unit.fromOffset()).append('\n');
                canonical.append(unit.toOffset()).append('\n');
                canonical.append(unit.ordinal()).append('\n');
            }
        }
        canonical.append(request.threadReaderManifest().schemaVersion()).append('\n');
        canonical.append(request.threadReaderManifest().fromOrdinal()).append('\n');
        canonical.append(request.threadReaderManifest().toOrdinal()).append('\n');
        canonical.append(request.threadReaderManifest().continuous()).append('\n');
        canonical.append(request.threadReaderManifest().manifestHash()).append('\n');
        for (EvidenceMessage message :
                request.threadReaderManifest().selectedEvidenceMessages()) {
            canonical.append(message.sourceUnitId()).append('\n');
            canonical.append(message.actorId()).append('\n');
            canonical.append(message.ordinal()).append('\n');
            canonical.append(message.externalUnitRef()).append('\n');
            canonical.append(message.occurredAt()).append('\n');
            canonical.append(message.bodyText()).append('\n');
            canonical.append(message.bodyHash()).append('\n');
        }
        canonical.append(request.confirmationProof());
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static UUID deterministicId(String label, UUID submissionId) {
        return UUID.nameUUIDFromBytes((label + submissionId).getBytes(StandardCharsets.UTF_8));
    }

    private static String buildReceiptManifest(UUID memoryId) {
        return "{\"type\":\"urn:pink:response:local-v1-closeout\",\"status\":202,"
                + "\"requestId\":\""
                + memoryId
                + "\",\"resultCategory\":\"SUCCEEDED\",\"retryable\":false}";
    }

    // ── static crypto helpers ─────────────────────────────────────────────

    private static LocalV1CloseoutException.Code failureCodeOf(RuntimeException exception) {
        if (exception instanceof LocalV1S1Exception s1Exception) {
            return map(s1Exception.failureCode());
        }
        if (exception instanceof CanonicalPublishException publishException) {
            return map(publishException.failureCode());
        }
        return LocalV1CloseoutException.Code.INTERNAL_FAILURE;
    }

    private static LocalV1CloseoutException.Code map(CanonicalFailureCode code) {
        return switch (code) {
            case IDEMPOTENCY_KEY_REUSED -> LocalV1CloseoutException.Code.IDEMPOTENCY_KEY_REUSED;
            case REVIEW_SESSION_NOT_OPEN -> LocalV1CloseoutException.Code.REVIEW_SESSION_NOT_OPEN;
            case REVIEW_MEMBER_MISMATCH -> LocalV1CloseoutException.Code.REVIEW_MEMBER_MISMATCH;
            case PROPOSAL_CONFLICT -> LocalV1CloseoutException.Code.PROPOSAL_CONFLICT;
            case CANONICAL_COMMIT_FAILED -> LocalV1CloseoutException.Code.CANONICAL_COMMIT_FAILED;
            default -> LocalV1CloseoutException.Code.INTERNAL_FAILURE;
        };
    }

    private static boolean is64Hex(String value) {
        return value != null
                && value.length() == 64
                && value.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String value) {
        return bytesToHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException exception) {
            throw new LocalV1CloseoutException(LocalV1CloseoutException.Code.INTERNAL_FAILURE, exception);
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private enum Gate {
        PROCEED,
        REPLAY
    }
}
