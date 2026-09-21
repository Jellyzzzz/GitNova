package orderflow.domain;

import java.util.List;

public record PlaceOrder(String requestId, String actorId, String destination,
                         List<OrderLine> lines, int percent) {
    public PlaceOrder {
        if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("invalid requestId");
        }
        if (actorId == null || !actorId.matches("[a-z][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("invalid actorId");
        }
        if (destination == null || !destination.matches("[a-z][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("invalid destination");
        }
        if (lines == null || lines.isEmpty() || lines.size() > 100) {
            throw new IllegalArgumentException("invalid order lines");
        }
        lines = List.copyOf(lines);
        Money.percentage(percent);
    }
}
