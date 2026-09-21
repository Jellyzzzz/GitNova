package orderflow.query;

import orderflow.audit.EventLog;
import orderflow.domain.Order;
import orderflow.domain.OrderEvent;
import orderflow.domain.OrderStatus;
import orderflow.order.OrderRepository;
import orderflow.pricing.Quote;
import java.util.List;

public final class OrderQuery {
    public record View(String id, String actorId, OrderStatus status, long version,
                       Quote quote, List<OrderEvent> events) {
        public View { events = List.copyOf(events); }
    }
    private final OrderRepository orders;
    private final EventLog events;

    public OrderQuery(OrderRepository orders, EventLog events) {
        this.orders = orders;
        this.events = events;
    }

    public View view(String actorId, String orderId) { return snapshot(orders.require(actorId, orderId)); }
    public List<View> all(String actorId) { return orders.forActor(actorId).stream().map(this::snapshot).toList(); }

    private View snapshot(Order order) {
        return new View(order.id(), order.actorId(), order.status(), order.version(),
                order.quote(), events.forOrder(order.id()));
    }
}
