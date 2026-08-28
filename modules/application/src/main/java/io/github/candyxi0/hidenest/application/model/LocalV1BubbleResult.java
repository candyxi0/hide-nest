package io.github.candyxi0.hidenest.application.model;

import java.util.List;

/** Closed Bubble result: BUBBLE_READY has one item; NO_MATCH has none. */
public record LocalV1BubbleResult(String status, List<LocalV1BubbleItem> items) {}
