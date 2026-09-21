package orderflow.request;

import orderflow.domain.PlaceOrder;
import java.util.TreeMap;

public final class RequestFingerprint {
    private RequestFingerprint() {}

    public static String of(PlaceOrder request) {
        var quantities = new TreeMap<String, Integer>();
        for (var line : request.lines()) quantities.merge(line.sku(), line.quantity(), Math::addExact);
        StringBuilder value = new StringBuilder(request.destination()).append('|').append(request.percent()).append('|');
        quantities.forEach((sku, quantity) -> value.append(sku).append(':').append(quantity).append(';'));
        return value.toString();
    }
}
