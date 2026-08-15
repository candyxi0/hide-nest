package io.github.candyxi0.hidenest.memory;

import java.net.http.HttpClient;

/** Synthetic negative fixture that imports a JDK HTTP type into the memory domain module. */
public final class MemoryForbiddenHttpFixture {

    private final HttpClient httpClient;

    public MemoryForbiddenHttpFixture(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public HttpClient httpClient() {
        return httpClient;
    }
}
