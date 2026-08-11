package io.github.candyxi0.hidenest.memory.port;

/** Vendor-neutral, bounded filter for the current-memory read path. */
public record MemoryReadFilter(String state, String keyword, int limit, int offset) {}
