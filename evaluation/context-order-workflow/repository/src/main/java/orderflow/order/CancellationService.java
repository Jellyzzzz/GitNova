package orderflow.order;

import orderflow.audit.EventLog;
import orderflow.domain.CancellationReceipt;
import orderflow.inventory.Inventory;

public final class CancellationService {
    private final OrderRepository orders;
    private final Inventory inventory;
    private final EventLog events;

    public CancellationService(OrderRepository orders, Inventory inventory, EventLog events) {
        this.orders = orders;
        this.inventory = inventory;
        this.events = events;
    }

    public CancellationReceipt cancel(String actorId, String orderId) {
        var order = orders.require(actorId, orderId);
        boolean released = inventory.release(orderId);
        order.cancel();
        events.append(orderId, "ORDER_CANCELLED");
        return new CancellationReceipt(orderId, released, order.status(), order.version());
    }
}
