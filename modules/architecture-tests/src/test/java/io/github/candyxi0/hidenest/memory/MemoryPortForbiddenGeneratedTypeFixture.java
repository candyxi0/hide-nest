package io.github.candyxi0.hidenest.memory;

import io.github.candyxi0.hidenest.database.generated.memory.tables.ActorRef;

/** Synthetic negative fixture that imports a jOOQ generated type from the memory port package. */
public final class MemoryPortForbiddenGeneratedTypeFixture {

    private MemoryPortForbiddenGeneratedTypeFixture() {}

    public static ActorRef useGeneratedType() {
        return ActorRef.ACTOR_REF;
    }
}
