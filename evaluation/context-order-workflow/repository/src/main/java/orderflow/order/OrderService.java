package orderflow.order;

import orderflow.audit.EventLog;
import orderflow.domain.Order;
import orderflow.domain.OrderReceipt;
import orderflow.domain.PlaceOrder;
import orderflow.inventory.Inventory;
import orderflow.pricing.PricingService;

public final class OrderService {
    private final OrderRepository orders;
    private final Inventory inventory;
    private final PricingService pricing;
    private final EventLog events;

    public OrderService(OrderRepository orders, Inventory inventory, PricingService pricing, EventLog events) {
        this.orders = orders;
        this.inventory = inventory;
        this.pricing = pricing;
        this.events = events;
    }

    /** Only the coordinator supplies request idempotency; direct placement is a new submission. */
    public OrderReceipt place(PlaceOrder request) {
        var quote = pricing.quote(request);
        String id = orders.nextId();
        inventory.reserve(id, request.lines());
        var order = new Order(id, request, quote);
        orders.save(order);
        events.append(id, "ORDER_RESERVED");
        return new OrderReceipt(id, quote, false);
    }

    public boolean pay(String actorId, String orderId) {
        Order order = orders.require(actorId, orderId);
        boolean changed = order.pay();
        if (changed) events.append(orderId, "ORDER_PAID");
        return changed;
    }
}
