package io.github.candyxi0.hidenest.security.domain;

public record CapabilityRef(
        String capabilityHash,
        String purpose,
        String targetKind,
        java.util.UUID targetId,
        Long targetRevisionRef,
        Long policyRevisionNo,
        boolean consumed) {}
