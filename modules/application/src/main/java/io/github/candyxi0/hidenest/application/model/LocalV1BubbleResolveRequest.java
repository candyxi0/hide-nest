package io.github.candyxi0.hidenest.application.model;

/** Platform-neutral Bubble request. Keys are opaque strings and queryText is never persisted. */
public record LocalV1BubbleResolveRequest(String spaceKey, String roomKey, String turnKey, String queryText) {}
