package io.github.candyxi0.hidenest.database.adapter;

import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;

public class JooqEvidenceReferenceAdapter implements EvidenceReferencePort {

    @SuppressWarnings("unused")
    private final DSLContext dsl;

    public JooqEvidenceReferenceAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void verifyAnchorsExist(Set<UUID> anchorIds) {
        // evidence schema has no tables (deferred to HDM-006).
        // Fail closed: non-empty anchor set cannot be verified.
        if (anchorIds != null && !anchorIds.isEmpty()) {
            throw new RuntimeException(
                    "CANONICAL_COMMIT_FAILED: evidence schema not available (deferred to HDM-006)");
        }
    }
}
