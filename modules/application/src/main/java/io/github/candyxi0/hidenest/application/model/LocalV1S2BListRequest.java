package io.github.candyxi0.hidenest.application.model;

/** Bounded local read request for the current-memory list. */
public record LocalV1S2BListRequest(String state, String keyword, int limit, int offset) {}
