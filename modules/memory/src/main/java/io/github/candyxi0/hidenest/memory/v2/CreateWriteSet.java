package io.github.candyxi0.hidenest.memory.v2;

import java.util.List;
import java.util.UUID;

/** Normalized in-memory command. References to other new items are intentionally unsupported in S03-A. */
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
            List<RevisionRef> relations) {
        public CreateItem {
            anchors = anchors == null ? null : List.copyOf(anchors);
            relations = relations == null ? null : List.copyOf(relations);
        }
    }

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

    public record RevisionRef(UUID revisionId, RelationKind kind) {}
}
