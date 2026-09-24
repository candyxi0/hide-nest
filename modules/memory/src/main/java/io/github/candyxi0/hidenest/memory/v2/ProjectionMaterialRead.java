package io.github.candyxi0.hidenest.memory.v2;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Internal, canonical input for one committed projection event. No JSON or delivery state. */
public final class ProjectionMaterialRead {
    private ProjectionMaterialRead() {}

    public enum Status {
        COMPLETE,
        INCOMPLETE
    }

    public enum Reason {
        EVENT_MISSING,
        WORLD_MISMATCH,
        EVENT_INCONSISTENT,
        PREDECESSOR_MISSING,
        RELATIONS_INVALID,
        MATERIAL_OUT_OF_BOUNDS,
        BUDGET_EXHAUSTED,
        READ_TIMEOUT,
        READ_FAILURE
    }

    public enum EventKind {
        MEMORY_CREATED,
        MEMORY_REVISED,
        MEMORY_SUPERSEDED
    }

    public enum MemoryType {
        EVENT,
        CLAIM,
        QUOTE,
        UNDERSTANDING
    }

    public record RevisionRef(String recordRef, String revisionRef) {
        public RevisionRef {
            Objects.requireNonNull(recordRef);
            Objects.requireNonNull(revisionRef);
        }
    }

    public record RelatedRevisionMaterial(
            String recordRef,
            String revisionRef,
            MemoryType type,
            String content,
            String subject,
            String scope,
            String perspective,
            String conditions,
            String timeContext,
            String uncertainty,
            List<String> supportingRevisionRefs,
            List<String> counterRevisionRefs) {
        public RelatedRevisionMaterial {
            supportingRevisionRefs = List.copyOf(supportingRevisionRefs);
            counterRevisionRefs = List.copyOf(counterRevisionRefs);
        }
    }

    public record Material(
            EventKind eventKind,
            String recordRef,
            String revisionRef,
            int revisionNo,
            MemoryType type,
            String content,
            String subject,
            String scope,
            String perspective,
            String conditions,
            String timeContext,
            String uncertainty,
            List<String> supportingRevisionRefs,
            List<String> counterRevisionRefs,
            RevisionRef predecessor,
            List<RevisionRef> successorRefs,
            List<RelatedRevisionMaterial> relatedRevisionMaterials) {
        public Material {
            supportingRevisionRefs = List.copyOf(supportingRevisionRefs);
            counterRevisionRefs = List.copyOf(counterRevisionRefs);
            successorRefs = List.copyOf(successorRefs);
            relatedRevisionMaterials = List.copyOf(relatedRevisionMaterials);
        }
    }

    public record Result(Status status, Reason reason, Material material) {
        public Result {
            if ((status == Status.COMPLETE) != (material != null) || (status == Status.COMPLETE) == (reason != null)) {
                throw new IllegalArgumentException("exactly one of material or incomplete reason is required");
            }
        }

        public static Result complete(Material material) {
            return new Result(Status.COMPLETE, null, Objects.requireNonNull(material));
        }

        public static Result incomplete(Reason reason) {
            return new Result(Status.INCOMPLETE, Objects.requireNonNull(reason), null);
        }
    }

    public record Limits(int timeoutMillis, int maxQueries) {
        public Limits {
            if (timeoutMillis < 1 || maxQueries < 0) throw new IllegalArgumentException("invalid read limits");
        }

        public static Limits defaults() {
            return new Limits(5000, 8);
        }
    }

    public interface Reader {
        Result read(String worldRef, UUID eventId, Limits limits);
    }
}
