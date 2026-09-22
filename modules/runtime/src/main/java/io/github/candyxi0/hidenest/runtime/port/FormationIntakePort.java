package io.github.candyxi0.hidenest.runtime.port;

import io.github.candyxi0.hidenest.runtime.domain.FormationPendingTask;
import io.github.candyxi0.hidenest.runtime.domain.SourceAdvance;
import io.github.candyxi0.hidenest.runtime.domain.SourceAdvanceResult;
import java.util.List;

/** Core-owned, body-free intake for source progress and unclaimed Formation work. */
public interface FormationIntakePort {

    SourceAdvanceResult recordSourceAdvanced(SourceAdvance advance);

    /** Read eligible PENDING work without claiming or changing generation. */
    List<FormationPendingTask> findReadyFormationTasks(int limit);
}
