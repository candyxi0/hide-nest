package io.github.candyxi0.hidenest.database.v2.adapter;

import io.github.candyxi0.hidenest.memory.v2.RecordSuccession;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** Internal read boundary. It never follows more than one succession edge. */
public final class JdbcRecordSuccessionReader implements RecordSuccession.Reader {
    private final DataSource dataSource;

    public JdbcRecordSuccessionReader(DataSource dataSource) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource);
    }

    @Override
    public Optional<RecordSuccession> findDirectSuccessor(UUID predecessorRecordId) throws SQLException {
        java.util.Objects.requireNonNull(predecessorRecordId);
        try (Connection c = dataSource.getConnection();
                PreparedStatement p =
                        c.prepareStatement("SELECT predecessor_record_id,predecessor_revision_id,successor_record_id,"
                                + "successor_revision_id,task_id,created_at FROM memory.record_succession "
                                + "WHERE predecessor_record_id=?")) {
            p.setObject(1, predecessorRecordId);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(new RecordSuccession(
                        r.getObject(1, UUID.class),
                        r.getObject(2, UUID.class),
                        r.getObject(3, UUID.class),
                        r.getObject(4, UUID.class),
                        r.getObject(5, UUID.class),
                        r.getObject(6, java.time.OffsetDateTime.class)));
            }
        }
    }
}
