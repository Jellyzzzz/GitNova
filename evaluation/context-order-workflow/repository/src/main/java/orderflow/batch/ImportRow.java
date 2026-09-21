package orderflow.batch;

import orderflow.domain.OrderLine;
import orderflow.domain.PlaceOrder;
import java.util.List;

public record ImportRow(String requestId, String actorId, String sku, int quantity,
                        int percent, String destination) {
    public static ImportRow parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("null import row");
        String[] cells = raw.split(",", -1);
        if (cells.length != 6) throw new IllegalArgumentException("expected six CSV columns");
        for (int i = 0; i < cells.length; i++) cells[i] = cells[i].trim();
        return new ImportRow(cells[0], cells[1], cells[2], Integer.parseInt(cells[3]),
                Integer.parseInt(cells[4]), cells[5]);
    }

    public PlaceOrder request() {
        return new PlaceOrder(requestId, actorId, destination, List.of(new OrderLine(sku, quantity)), percent);
    }
}
