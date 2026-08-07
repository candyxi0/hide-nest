package io.github.candyxi0.hidenest.evidence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Synthetic negative fixture for ArchUnit rule validation.
 * Resides in the evidence domain package and explicitly depends on java.sql.Connection.
 * Must be detected by {@code forbiddenFrameworkDependencyRule()} in ArchitectureTest.
 */
public final class EvidenceForbiddenFrameworkFixture {

    private EvidenceForbiddenFrameworkFixture() {}

    public static Connection openConnection(String url) throws SQLException {
        return DriverManager.getConnection(url);
    }
}
