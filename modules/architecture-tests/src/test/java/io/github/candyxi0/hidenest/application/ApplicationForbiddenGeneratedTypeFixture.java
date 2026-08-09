package io.github.candyxi0.hidenest.application;

import io.github.candyxi0.hidenest.database.generated.memory.tables.ActorRef;

/** Synthetic fixture that violates the generated-type boundary rule. */
public final class ApplicationForbiddenGeneratedTypeFixture {

    private final ActorRef generatedTable;

    public ApplicationForbiddenGeneratedTypeFixture(ActorRef generatedTable) {
        this.generatedTable = generatedTable;
    }

    public ActorRef generatedTable() {
        return generatedTable;
    }
}
