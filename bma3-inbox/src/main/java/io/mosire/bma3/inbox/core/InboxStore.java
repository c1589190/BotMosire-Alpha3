package io.mosire.bma3.inbox.core;

import java.util.List;
import java.util.Optional;

/** Persistence port for one inbox. */
public interface InboxStore extends AutoCloseable {

    void append(InboxItem item);

    void update(InboxItem item);

    Optional<InboxItem> item(String id);

    /** The current unread item for one thread/source, if any. */
    Optional<InboxItem> activeForSource(String source);

    List<InboxItem> items();

    void saveDelivery(Delivery delivery);

    Optional<Delivery> delivery(String id);

    List<Delivery> deliveries();

    @Override
    void close();
}
