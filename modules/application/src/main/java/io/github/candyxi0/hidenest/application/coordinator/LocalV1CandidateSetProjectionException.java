package io.github.candyxi0.hidenest.application.coordinator;

/**
 * Fail-closed exception for the Local V1 CandidateSet CREATE projection vertical.
 *
 * <p>Every {@link Code} value maps to an existing value in the frozen 50-code registry; this enum
 * is an application-level classification, not a new formal failure code. When an exact read port
 * cannot be satisfied without widening the adapter surface, the coordinator fails closed with
 * {@link Code#BLOCKED_CANDIDATE_SET_PROJECTION_READ_PORT_GAP} rather than reaching into JDBC/jOOQ.</p>
 */
public class LocalV1CandidateSetProjectionException extends RuntimeException {

    public enum Code {
        CANDIDATE_SET_NOT_FOUND,
        REVIEW_SESSION_NOT_COMPLETED,
        MEMBER_CLOSURE_INVALID,
        PROPOSAL_REVISION_MISMATCH,
        DECISION_BINDING_MISMATCH,
        UNSUPPORTED_CANDIDATE_ACTION,
        MAPPING_CLOSURE_INVALID,
        ORPHAN_EVIDENCE_ANCHOR,
        DUPLICATE_PROJECTION_IDENTITY,
        PUBLISHED_MEMORY_VERIFICATION_FAILED,
        CANONICAL_PUBLISH_FAILED,
        BLOCKED_CANDIDATE_SET_PROJECTION_READ_PORT_GAP,
        INTERNAL_FAILURE
    }

    private final Code code;

    public LocalV1CandidateSetProjectionException(Code code) {
        super(code.name());
        this.code = code;
    }

    public LocalV1CandidateSetProjectionException(Code code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
