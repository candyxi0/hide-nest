package io.github.candyxi0.hidenest.application.model;

/** Exact room identity accepted by the narrow Bubble purge path. */
public record LocalV1BubbleRoomPurgeRequest(String spaceKey, String roomKey) {}
