package stockroom;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ReservationService {
    private final Inventory inventory;
    private final Map<String, Reservation> reservations = new LinkedHashMap<>();

    public ReservationService(Inventory inventory) { this.inventory = inventory; }

    public Reservation reserve(String requestId, String sku, int quantity, long ttl, long now) {
        if (requestId == null || requestId.isBlank() || ttl <= 0 || quantity <= 0) {
            throw new IllegalArgumentException("invalid reservation");
        }
        Reservation previous = reservations.get(requestId);
        if (previous != null) {
            if (!previous.sku.equals(sku) || previous.quantity != quantity || previous.ttl != ttl) {
                throw new IllegalArgumentException("idempotency conflict");
            }
            return previous;
        }
        int available = inventory.available(sku);
        if (quantity > available) throw new IllegalArgumentException("insufficient stock");
        long expiresAt = Math.addExact(now, ttl);
        Reservation reservation = new Reservation(requestId, sku, quantity, ttl, expiresAt);
        inventory.set(sku, available - quantity);
        reservations.put(requestId, reservation);
        return reservation;
    }

    public int expire(long now) {
        int released = 0;
        for (Reservation reservation : reservations.values()) {
            if (reservation.active() && now >= reservation.expiresAt) {
                inventory.set(reservation.sku, Math.addExact(inventory.available(reservation.sku), reservation.quantity));
                reservation.deactivate();
                released++;
            }
        }
        return released;
    }
}
