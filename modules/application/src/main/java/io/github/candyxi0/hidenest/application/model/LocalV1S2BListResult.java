package io.github.candyxi0.hidenest.application.model;

import java.util.List;

public record LocalV1S2BListResult(List<LocalV1S2BMemoryItem> items) {

    public LocalV1S2BListResult {
        items = List.copyOf(items);
    }
}
