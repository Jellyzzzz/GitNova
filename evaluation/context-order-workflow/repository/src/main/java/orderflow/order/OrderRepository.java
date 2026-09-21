package orderflow.order;

import orderflow.domain.Order;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OrderRepository {
    private final Map<String, Order> orders = new LinkedHashMap<>();
    private long allocated;

    /** IDs are unique, not guaranteed gap-free after a rejected submission. */
    public String nextId() { return "O-" + (++allocated); }

    public void save(Order order) {
        if (orders.putIfAbsent(order.id(), order) != null) throw new IllegalStateException("duplicate order");
    }

    public Order require(String actorId, String orderId) {
        Order order = orders.get(orderId);
        if (order == null || !order.actorId().equals(actorId)) {
            throw new IllegalArgumentException("order not available");
        }
        return order;
    }

    public List<Order> forActor(String actorId) {
        return orders.values().stream().filter(order -> order.actorId().equals(actorId)).toList();
    }

    public int size() { return orders.size(); }
}
