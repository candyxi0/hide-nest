package io.github.candyxi0.hidenest.evidence;

import io.github.candyxi0.hidenest.database.generated.evidence.tables.Source;

/** Synthetic negative fixture that imports a jOOQ generated type from the evidence port package. */
public final class EvidencePortForbiddenGeneratedTypeFixture {

    private EvidencePortForbiddenGeneratedTypeFixture() {}

    public static Source useGeneratedType() {
        return Source.SOURCE;
    }
}
