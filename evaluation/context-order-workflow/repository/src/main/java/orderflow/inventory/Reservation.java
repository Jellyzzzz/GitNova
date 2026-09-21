package orderflow.inventory;

import java.util.Map;

public record Reservation(String orderId, Map<String, Integer> quantities) {
    public Reservation { quantities = Map.copyOf(quantities); }
}
