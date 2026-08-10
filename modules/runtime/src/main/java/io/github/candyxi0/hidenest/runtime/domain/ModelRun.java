package io.github.candyxi0.hidenest.runtime.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ModelRun(
        UUID modelRunId,
        String roleCode,
        String providerManifestId,
        String state,
        byte[] inputManifestHash,
        byte[] outputManifestHash,
        UUID retryOf,
        OffsetDateTime startedAt,
        OffsetDateTime terminalAt,
        String failureCode) {}
