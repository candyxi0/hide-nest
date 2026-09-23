package io.github.candyxi0.hidenest.memory.v2;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Internal canonical read. A completed result reports version facts, never truth. */
public final class DependencyRead {
    private DependencyRead() {}

    public enum Status {
        COMPLETE,
        RECORD_MISSING,
        WORLD_MISMATCH,
        INCOMPLETE
    }

    public enum Observation {
        SUPPORT_CHANGED,
        NO_VERSION_CHANGE_OBSERVED
    }

    public record Limits(int maxRevisions, int maxRelations, int maxWitnesses, int timeoutMillis) {
        public Limits {
            if (maxRevisions < 1 || maxRelations < 1 || maxWitnesses < 1 || timeoutMillis < 1)
                throw new IllegalArgumentException("all read limits must be positive");
        }

        public static Limits defaults() {
            return new Limits(256, 1024, 64, 5000);
        }
    }

    public record Revision(
            UUID recordId,
            UUID revisionId,
            int revisionNo,
            String type,
            String participation,
            UUID currentRevisionId,
            String content,
            String subject,
            String scope,
            String perspective,
            String conditions,
            String timeContext,
            String uncertainty,
            RecordSuccession directSuccessor) {}

    public record Relation(UUID fromRevisionId, UUID targetRevisionId, String kind, Revision target) {}

    /** Anchor identifiers and source coordinates; the original exact_text is deliberately absent. */
    public record Evidence(
            UUID revisionId,
            UUID anchorId,
            UUID unitId,
            UUID sourceId,
            String sourceRef,
            String sourceVersion,
            String locator,
            String frame,
            String actor,
            String speakingAs) {}

    public record Witness(List<Relation> supportPath, Revision changedTarget) {
        public Witness {
            supportPath = List.copyOf(supportPath);
        }
    }

    public record Result(
            Status status,
            Observation observation,
            String detail,
            Revision root,
            List<Revision> revisions,
            List<Relation> relations,
            List<Witness> witnesses,
            List<Evidence> evidence,
            OffsetDateTime snapshotAt) {
        public Result {
            revisions = List.copyOf(revisions);
            relations = List.copyOf(relations);
            witnesses = List.copyOf(witnesses);
            evidence = List.copyOf(evidence);
        }

        public static Result unavailable(Status status, String detail) {
            return new Result(status, null, detail, null, List.of(), List.of(), List.of(), List.of(), null);
        }
    }

    public interface Reader {
        Result read(String worldRef, UUID recordId, Limits limits);
    }
}
