package stockroom;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Inventory {
    public static final int MAX = 1_000_000;
    private final Map<String, Integer> stock = new LinkedHashMap<>();

    public Inventory(Map<String, Integer> initial) {
        for (var entry : initial.entrySet()) {
            if (entry.getKey().isBlank() || entry.getValue() < 0 || entry.getValue() > MAX) {
                throw new IllegalArgumentException("invalid inventory");
            }
            stock.put(entry.getKey(), entry.getValue());
        }
    }

    public int available(String sku) {
        Integer value = stock.get(sku);
        if (value == null) throw new IllegalArgumentException("unknown sku: " + sku);
        return value;
    }

    public void set(String sku, int value) {
        available(sku);
        if (value < 0 || value > MAX) throw new IllegalArgumentException("stock out of bounds");
        stock.put(sku, value);
    }

    public Map<String, Integer> snapshot() { return Map.copyOf(stock); }
}
