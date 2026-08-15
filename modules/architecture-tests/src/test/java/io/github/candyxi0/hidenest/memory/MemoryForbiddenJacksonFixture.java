package io.github.candyxi0.hidenest.memory;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Synthetic negative fixture that imports a Jackson type into the memory domain module. */
public final class MemoryForbiddenJacksonFixture {

    private final ObjectMapper objectMapper;

    public MemoryForbiddenJacksonFixture(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }
}
