package io.github.candyxi0.hidenest.memory.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AccessPolicyRevision(
        UUID policyId,
        Long revisionNo,
        Boolean companionAllowed,
        Boolean maintenanceAllowed,
        Boolean exportAllowed,
        Boolean externalProviderAllowed,
        Boolean isolated,
        UUID createdByDecisionId,
        OffsetDateTime createdAt) {}
