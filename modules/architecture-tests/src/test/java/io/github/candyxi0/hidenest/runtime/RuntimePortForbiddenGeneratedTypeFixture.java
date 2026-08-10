package io.github.candyxi0.hidenest.runtime;

import io.github.candyxi0.hidenest.database.generated.runtime.tables.CaptureScope;

/**
 * Deliberately violates the port boundary rule by importing a jOOQ generated table type.
 * Used by PortBoundaryTest to prove the rule factory actually rejects violations.
 */
@SuppressWarnings("unused")
public class RuntimePortForbiddenGeneratedTypeFixture {

    public void leakGeneratedType() {
        Class<?> forbidden = CaptureScope.class;
    }
}
