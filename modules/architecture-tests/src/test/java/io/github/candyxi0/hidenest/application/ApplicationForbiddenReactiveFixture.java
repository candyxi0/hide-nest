package io.github.candyxi0.hidenest.application;

import io.r2dbc.spi.ConnectionFactory;

/** Synthetic fixture that violates the project-wide reactive database API boundary. */
public final class ApplicationForbiddenReactiveFixture {

    private final ConnectionFactory connectionFactory;

    public ApplicationForbiddenReactiveFixture(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public ConnectionFactory connectionFactory() {
        return connectionFactory;
    }
}
