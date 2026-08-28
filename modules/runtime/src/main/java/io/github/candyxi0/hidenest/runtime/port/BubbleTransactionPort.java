package io.github.candyxi0.hidenest.runtime.port;

import io.github.candyxi0.hidenest.runtime.domain.BubbleDeliveryItem;
import io.github.candyxi0.hidenest.runtime.domain.BubbleRoomRevisionLedgerEntry;
import io.github.candyxi0.hidenest.runtime.domain.BubbleTurnReceipt;

/** Write and serialization side of the platform-neutral Bubble fact store. */
public interface BubbleTransactionPort {
    void lockTurnKey(String turnKey);

    void lockRoom(String spaceKey, String roomKey);

    void insertReceipt(BubbleTurnReceipt receipt);

    void insertDeliveryItem(BubbleDeliveryItem item);

    void insertLedgerEntry(BubbleRoomRevisionLedgerEntry entry);

    void purgeRoom(String spaceKey, String roomKey);
}
