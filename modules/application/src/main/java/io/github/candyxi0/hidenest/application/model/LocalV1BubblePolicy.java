package io.github.candyxi0.hidenest.application.model;

/** Fail-closed server policy for Bubble V1. */
public record LocalV1BubblePolicy(String defaultSpaceKey, double minScore, String policyVersion) {}
