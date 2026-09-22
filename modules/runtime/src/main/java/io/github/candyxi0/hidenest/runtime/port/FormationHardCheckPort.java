package io.github.candyxi0.hidenest.runtime.port;

import io.github.candyxi0.hidenest.runtime.domain.FormationSettlementCandidate;
import java.sql.Connection;
import java.sql.SQLException;

/** S03 supplies reference, current and governance checks on the settlement transaction. */
@FunctionalInterface
public interface FormationHardCheckPort {
    boolean valid(Connection connection, FormationSettlementCandidate candidate) throws SQLException;
}
