package io.github.candyxi0.hidenest.application.model;

/** Exact minimal Bubble projection permitted to reach a model-facing caller. */
public record LocalV1BubbleItem(String bodyText, String memoryType, int evidenceAgeDays) {}
