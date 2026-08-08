package io.github.candyxi0.hidenest.database;

import io.github.candyxi0.hidenest.application.ApplicationModule;

/** Synthetic fixture that violates the formal database adapter boundary rule. */
public final class DatabaseForbiddenApplicationFixture {

    private final ApplicationModule application;

    public DatabaseForbiddenApplicationFixture(ApplicationModule application) {
        this.application = application;
    }

    public ApplicationModule application() {
        return application;
    }
}
