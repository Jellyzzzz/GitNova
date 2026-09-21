package orderflow.inventory;

import orderflow.catalog.Catalog;
import orderflow.domain.OrderLine;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** All checks precede deductions. This class is intentionally single-threaded. */
public final class Inventory {
    private final Map<String, Integer> available = new HashMap<>();
    private final Map<String, Reservation> reservations = new HashMap<>();
    private final Set<String> released = new HashSet<>();

    public Inventory(Catalog catalog) {
        for (var product : catalog.all()) available.put(product.sku(), product.initialStock());
    }

    public int available(String sku) {
        Integer count = available.get(sku);
        if (count == null) throw new IllegalArgumentException("unknown SKU: " + sku);
        return count;
    }

    public Reservation reserve(String orderId, List<OrderLine> lines) {
        if (orderId == null || orderId.isBlank() || reservations.containsKey(orderId)) {
            throw new IllegalArgumentException("invalid reservation identity");
        }
        if (lines.isEmpty()) throw new IllegalArgumentException("empty reservation");
        Map<String, Integer> quantities = new HashMap<>();
        for (OrderLine line : lines) quantities.merge(line.sku(), line.quantity(), Math::addExact);
        for (var entry : quantities.entrySet()) {
            if (available(entry.getKey()) < entry.getValue()) {
                throw new IllegalStateException("insufficient stock: " + entry.getKey());
            }
        }
        for (var entry : quantities.entrySet()) {
            available.put(entry.getKey(), available(entry.getKey()) - entry.getValue());
        }
        var reservation = new Reservation(orderId, quantities);
        reservations.put(orderId, reservation);
        return reservation;
    }

    public boolean release(String orderId) {
        Reservation reservation = reservation(orderId);
        if (released.contains(orderId)) return false;
        for (var entry : reservation.quantities().entrySet()) {
            available.put(entry.getKey(), Math.addExact(available(entry.getKey()), entry.getValue()));
        }
        released.add(orderId);
        return true;
    }

    public Reservation reservation(String orderId) {
        Reservation reservation = reservations.get(orderId);
        if (reservation == null) throw new IllegalArgumentException("unknown reservation");
        return reservation;
    }

    public boolean released(String orderId) { return released.contains(orderId); }
    public Map<String, Integer> snapshot() { return Map.copyOf(available); }
    public int reservationCount() { return reservations.size(); }
}
