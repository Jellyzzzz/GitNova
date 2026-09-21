package orderflow.domain;

import orderflow.pricing.Quote;
import java.util.List;
import java.util.Objects;

/** Mutable aggregate owned by the sequential in-memory application. */
public final class Order {
    private final String id;
    private final String actorId;
    private final String destination;
    private final List<OrderLine> lines;
    private final Quote quote;
    private OrderStatus status = OrderStatus.RESERVED;
    private long version;

    public Order(String id, PlaceOrder request, Quote quote) {
        this.id = Objects.requireNonNull(id);
        this.actorId = request.actorId();
        this.destination = request.destination();
        this.lines = request.lines();
        this.quote = Objects.requireNonNull(quote);
    }

    public String id() { return id; }
    public String actorId() { return actorId; }
    public String destination() { return destination; }
    public List<OrderLine> lines() { return lines; }
    public Quote quote() { return quote; }
    public OrderStatus status() { return status; }
    public long version() { return version; }

    public boolean pay() {
        if (status == OrderStatus.CANCELLED) throw new IllegalStateException("cancelled order");
        if (status == OrderStatus.PAID) return false;
        status = OrderStatus.PAID;
        version++;
        return true;
    }

    public void cancel() {
        if (status == OrderStatus.PAID) throw new IllegalStateException("paid order cannot be cancelled");
        if (status == OrderStatus.CANCELLED) return;
        status = OrderStatus.CANCELLED;
        version++;
    }
}
