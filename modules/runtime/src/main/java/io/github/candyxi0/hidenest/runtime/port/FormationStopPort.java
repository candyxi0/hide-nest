package io.github.candyxi0.hidenest.runtime.port;

import java.util.UUID;

/** Best-effort stop signal after a competing result commits. */
@FunctionalInterface
public interface FormationStopPort {
    void requestStop(UUID attemptId);
}
