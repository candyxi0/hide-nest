package io.github.candyxi0.hidenest.evidence;

import io.github.candyxi0.hidenest.database.DatabaseAdapterModule;

/** Synthetic fixture that violates the formal domain boundary rule. */
public final class EvidenceForbiddenDatabaseFixture {

    private final DatabaseAdapterModule databaseAdapter;

    public EvidenceForbiddenDatabaseFixture(DatabaseAdapterModule databaseAdapter) {
        this.databaseAdapter = databaseAdapter;
    }

    public DatabaseAdapterModule databaseAdapter() {
        return databaseAdapter;
    }
}
