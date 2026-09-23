package io.github.candyxi0.hidenest.memory.v2;

import java.util.List;
import java.util.UUID;

/** Normalized in-memory publication command. */
public record CreateWriteSet(
        String worldRef, UUID sourceId, String sourceRef, String sourceVersion, List<CreateItem> items) {
    public CreateWriteSet {
        items = items == null ? null : List.copyOf(items);
    }

    public record CreateItem(
            String action,
            MemoryType type,
            String content,
            String subject,
            String scope,
            String perspective,
            String conditions,
            String timeContext,
            String uncertainty,
            String formationRef,
            List<AnchorRef> anchors,
            List<RevisionRef> relations,
            String itemRef,
            ExpectedCurrent expectedCurrent) {
        public CreateItem {
            anchors = anchors == null ? null : List.copyOf(anchors);
            relations = relations == null ? null : List.copyOf(relations);
        }

        public CreateItem(
                String action,
                MemoryType type,
                String content,
                String subject,
                String scope,
                String perspective,
                String conditions,
                String timeContext,
                String uncertainty,
                String formationRef,
                List<AnchorRef> anchors,
                List<RevisionRef> relations) {
            this(
                    action,
                    type,
                    content,
                    subject,
                    scope,
                    perspective,
                    conditions,
                    timeContext,
                    uncertainty,
                    formationRef,
                    anchors,
                    relations,
                    null,
                    null);
        }
    }

    public record ExpectedCurrent(UUID recordId, UUID revisionId) {}

    public enum MemoryType {
        EVENT,
        CLAIM,
        QUOTE,
        UNDERSTANDING
    }

    public enum RelationKind {
        SUPPORT,
        COUNTER
    }

    public record AnchorRef(String locator, String exactText, String frame, String actor, String speakingAs) {}

    /** Exactly one target is present: a committed revision or a name local to this WriteSet. */
    public record RevisionRef(UUID revisionId, String itemRef, RelationKind kind) {
        public RevisionRef(UUID revisionId, RelationKind kind) {
            this(revisionId, null, kind);
        }

        public static RevisionRef item(String itemRef, RelationKind kind) {
            return new RevisionRef(null, itemRef, kind);
        }
    }
}
