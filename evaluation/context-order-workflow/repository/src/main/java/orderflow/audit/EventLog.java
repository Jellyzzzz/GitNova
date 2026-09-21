package orderflow.audit;

import orderflow.domain.OrderEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

public final class EventLog {
    private final List<OrderEvent> events = new ArrayList<>();
    private final LongSupplier clock;

    public EventLog(LongSupplier clock) { this.clock = clock; }

    public OrderEvent append(String orderId, String type) {
        var event = new OrderEvent(events.size() + 1L, orderId, type, clock.getAsLong());
        events.add(event);
        return event;
    }

    public List<OrderEvent> forOrder(String orderId) {
        return events.stream().filter(event -> event.orderId().equals(orderId)).toList();
    }

    public List<OrderEvent> all() { return List.copyOf(events); }
    public int size() { return events.size(); }
}
