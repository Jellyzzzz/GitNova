package orderflow.order;

import orderflow.audit.EventLog;
import orderflow.domain.CancellationReceipt;
import orderflow.domain.OrderStatus;
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
        if (order.status() == OrderStatus.PAID) throw new IllegalStateException("paid order cannot be cancelled");
        if (order.status() == OrderStatus.CANCELLED) {
            return new CancellationReceipt(orderId, false, order.status(), order.version());
        }
        inventory.release(orderId);
        order.cancel();
        events.append(orderId, "ORDER_CANCELLED");
        return new CancellationReceipt(orderId, true, order.status(), order.version());
    }
}
