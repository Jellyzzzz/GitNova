package orderflow.request;

import orderflow.domain.PlaceOrder;

/** Stable representation of business intent; it is not a security credential. */
public final class RequestFingerprint {
    private RequestFingerprint() {}

    public static String of(PlaceOrder request) {
        StringBuilder value = new StringBuilder();
        for (var line : request.lines()) {
            value.append(line.sku()).append(':').append(line.quantity()).append(';');
        }
        return value.toString();
    }
}
