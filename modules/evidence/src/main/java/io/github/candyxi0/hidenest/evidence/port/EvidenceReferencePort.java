package io.github.candyxi0.hidenest.evidence.port;

import io.github.candyxi0.hidenest.evidence.domain.Source;
import io.github.candyxi0.hidenest.evidence.domain.SourceAnchor;
import io.github.candyxi0.hidenest.evidence.domain.SourceAnchorUnit;
import io.github.candyxi0.hidenest.evidence.domain.SourcePayload;
import io.github.candyxi0.hidenest.evidence.domain.SourceUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface EvidenceReferencePort {

    /** Insert a source. */
    void insertSource(Source source);

    /** Find source by primary key. */
    Source findSourceById(UUID sourceId);

    /** Insert a source unit. */
    void insertSourceUnit(SourceUnit unit);

    /** Find source unit by primary key. */
    SourceUnit findSourceUnitById(UUID sourceUnitId);

    /** Insert source payload metadata. Must not persist body text. */
    void insertSourcePayload(SourcePayload payload);

    /** Find source payload metadata by primary key. Must not return body text. */
    SourcePayload findSourcePayloadById(UUID payloadId);

    /** Insert a source anchor. */
    void insertSourceAnchor(SourceAnchor anchor);

    /** Find source anchor by primary key. */
    SourceAnchor findSourceAnchorById(UUID anchorId);

    /** Insert anchor units. Supports batch or single insertion. */
    void insertSourceAnchorUnits(List<SourceAnchorUnit> units);

    /** Find anchor units by anchor id. */
    List<SourceAnchorUnit> findSourceAnchorUnitsByAnchorId(UUID anchorId);

    /** Verify that the given anchor IDs exist in the database. */
    void verifyAnchorsExist(Set<UUID> anchorIds);

    /** Find source by platform and external_ref (deterministic binding). */
    Source findSourceByExternalRef(String platform, String externalRef);

    /** Find all anchors belonging to a source. */
    List<SourceAnchor> findSourceAnchorsBySourceId(UUID sourceId);
}
