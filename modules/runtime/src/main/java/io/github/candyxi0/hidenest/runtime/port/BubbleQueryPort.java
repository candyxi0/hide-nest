package io.github.candyxi0.hidenest.runtime.port;

import io.github.candyxi0.hidenest.runtime.domain.BubbleDeliveryItem;
import io.github.candyxi0.hidenest.runtime.domain.BubbleTurnReceipt;
import java.util.Set;
import java.util.UUID;

/** Read side of the platform-neutral Bubble fact store. */
public interface BubbleQueryPort {
    BubbleTurnReceipt findReceiptByTurnKey(String turnKey);

    BubbleDeliveryItem findDeliveryItem(String spaceKey, String roomKey, String turnKey);

    Set<UUID> findDeliveredRevisionIds(String spaceKey, String roomKey);
}
