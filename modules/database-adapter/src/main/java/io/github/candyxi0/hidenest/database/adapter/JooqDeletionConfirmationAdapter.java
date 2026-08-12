package io.github.candyxi0.hidenest.database.adapter;

import static io.github.candyxi0.hidenest.database.generated.memory.Tables.DELETION_CLOSURE;
import static io.github.candyxi0.hidenest.database.generated.memory.Tables.DELETION_CLOSURE_MEMBER;

import io.github.candyxi0.hidenest.database.generated.memory.tables.records.DeletionClosureRecord;
import io.github.candyxi0.hidenest.memory.port.DeletionConfirmationPort;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;

/** PostgreSQL/jOOQ implementation of the S3B2A confirmation storage boundary. */
public final class JooqDeletionConfirmationAdapter implements DeletionConfirmationPort {

    private final DSLContext dsl;

    public JooqDeletionConfirmationAdapter(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public ConfirmationSnapshot lockConfirmationSnapshot(UUID closureId) {
        Objects.requireNonNull(closureId, "closureId");
        DeletionClosureRecord closure = dsl.selectFrom(DELETION_CLOSURE)
                .where(DELETION_CLOSURE.CLOSURE_ID.eq(closureId))
                .forUpdate()
                .fetchOne();
        if (closure == null) return null;

        List<Member> members = new ArrayList<>();
        dsl.selectFrom(DELETION_CLOSURE_MEMBER)
                .where(DELETION_CLOSURE_MEMBER.CLOSURE_ID.eq(closureId))
                .orderBy(DELETION_CLOSURE_MEMBER.ORDINAL.asc())
                .fetch()
                .forEach(row -> members.add(new Member(
                        row.getOrdinal(), row.getMemberKind(), row.getTargetId(), row.getTargetRevisionRef(),
                        row.getDisposition(), row.getSizeBytes(), row.getContentHash())));
        return new ConfirmationSnapshot(
                closure.getClosureId(), closure.getRootMemoryId(), positive(closure.getPreviewRevision(), "previewRevision"),
                require(closure.getRootCurrentRevisionId(), "rootCurrentRevisionId"),
                positive(closure.getRootRevisionNo(), "rootRevisionNo"),
                require(closure.getRootPolicyId(), "rootPolicyId"),
                positive(closure.getRootPolicyRevisionNo(), "rootPolicyRevisionNo"),
                requireHash(closure.getRequestHash(), "requestHash"),
                requireHash(closure.getManifestHash(), "manifestHash"),
                requireText(closure.getState(), "state"),
                require(closure.getCreatedAt(), "createdAt"), require(closure.getExpiresAt(), "expiresAt"),
                members, closure.getConfirmedByDecisionId(), closure.getConfirmedAt());
    }

    @Override
    public boolean confirmClosure(
            UUID closureId,
            long expectedPreviewRevision,
            byte[] expectedManifestHash,
            UUID expectedRootCurrentRevisionId,
            long expectedRootRevisionNo,
            UUID expectedRootPolicyId,
            long expectedRootPolicyRevisionNo,
            UUID decisionId,
            OffsetDateTime confirmedAt) {
        Objects.requireNonNull(closureId, "closureId");
        if (expectedPreviewRevision < 1) throw new IllegalArgumentException("expectedPreviewRevision must be positive");
        byte[] manifestHash = requireHash(expectedManifestHash, "expectedManifestHash");
        Objects.requireNonNull(expectedRootCurrentRevisionId, "expectedRootCurrentRevisionId");
        if (expectedRootRevisionNo < 1) throw new IllegalArgumentException("expectedRootRevisionNo must be positive");
        Objects.requireNonNull(expectedRootPolicyId, "expectedRootPolicyId");
        if (expectedRootPolicyRevisionNo < 1) throw new IllegalArgumentException("expectedRootPolicyRevisionNo must be positive");
        Objects.requireNonNull(decisionId, "decisionId");
        Objects.requireNonNull(confirmedAt, "confirmedAt");

        return dsl.update(DELETION_CLOSURE)
                .set(DELETION_CLOSURE.STATE, "CONFIRMED")
                .set(DELETION_CLOSURE.CONFIRMED_BY_DECISION_ID, decisionId)
                .set(DELETION_CLOSURE.CONFIRMED_AT, confirmedAt)
                .where(DELETION_CLOSURE.CLOSURE_ID.eq(closureId))
                .and(DELETION_CLOSURE.STATE.eq("PREVIEWED"))
                .and(DELETION_CLOSURE.PREVIEW_REVISION.eq(expectedPreviewRevision))
                .and(DELETION_CLOSURE.MANIFEST_HASH.eq(manifestHash))
                .and(DELETION_CLOSURE.ROOT_CURRENT_REVISION_ID.eq(expectedRootCurrentRevisionId))
                .and(DELETION_CLOSURE.ROOT_REVISION_NO.eq(expectedRootRevisionNo))
                .and(DELETION_CLOSURE.ROOT_POLICY_ID.eq(expectedRootPolicyId))
                .and(DELETION_CLOSURE.ROOT_POLICY_REVISION_NO.eq(expectedRootPolicyRevisionNo))
                .execute() == 1;
    }

    private static long positive(Long value, String name) {
        if (value == null || value < 1) throw new IllegalStateException(name + " must be positive");
        return value;
    }

    private static <T> T require(T value, String name) {
        return Objects.requireNonNull(value, name);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must not be blank");
        return value;
    }

    private static byte[] requireHash(byte[] value, String name) {
        if (value == null || value.length != 32) throw new IllegalArgumentException(name + " must be exactly 32 bytes");
        return Arrays.copyOf(value, value.length);
    }
}
