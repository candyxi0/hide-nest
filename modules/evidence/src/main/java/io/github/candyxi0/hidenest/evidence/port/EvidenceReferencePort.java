package io.github.candyxi0.hidenest.evidence.port;

import io.github.candyxi0.hidenest.evidence.domain.AnchorRef;
import java.util.Set;
import java.util.UUID;

public interface EvidenceReferencePort {

    void verifyAnchorsExist(Set<UUID> anchorIds);
}
