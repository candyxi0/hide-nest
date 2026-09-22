package io.github.candyxi0.hidenest.runtime.port;

import io.github.candyxi0.hidenest.runtime.domain.FormationSettlementCandidate;
import java.sql.Connection;
import java.sql.SQLException;

/** S03 canonical write boundary; invoked inside the same short database transaction. */
@FunctionalInterface
public interface CanonicalCommitPort {
    void commit(Connection connection, FormationSettlementCandidate candidate) throws SQLException;
}
