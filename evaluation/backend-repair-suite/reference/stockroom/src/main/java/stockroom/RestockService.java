package stockroom;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RestockService {
    public void importCsv(Inventory inventory, String csv) {
        Map<String, Integer> planned = new LinkedHashMap<>();
        for (StockCsv.Row row : new StockCsv().parse(csv)) {
            if (row.quantity() <= 0 || planned.containsKey(row.sku())) throw new IllegalArgumentException("invalid batch");
            int updated = Math.addExact(inventory.available(row.sku()), row.quantity());
            if (updated > Inventory.MAX) throw new IllegalArgumentException("stock overflow");
            planned.put(row.sku(), updated);
        }
        for (var entry : planned.entrySet()) inventory.set(entry.getKey(), entry.getValue());
    }
}
