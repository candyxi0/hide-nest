package io.github.candyxi0.hidenest.application;

import io.github.candyxi0.hidenest.database.DatabaseAdapterModule;
import java.sql.Connection;

/** Synthetic fixture that violates the formal application boundary rule. */
public final class ApplicationForbiddenDependencyFixture {

    private final DatabaseAdapterModule databaseAdapter;
    private final Connection connection;

    public ApplicationForbiddenDependencyFixture(DatabaseAdapterModule databaseAdapter, Connection connection) {
        this.databaseAdapter = databaseAdapter;
        this.connection = connection;
    }

    public DatabaseAdapterModule databaseAdapter() {
        return databaseAdapter;
    }

    public Connection connection() {
        return connection;
    }
}
