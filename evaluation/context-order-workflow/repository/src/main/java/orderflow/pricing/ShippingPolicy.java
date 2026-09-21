package orderflow.pricing;

import orderflow.domain.Money;

public final class ShippingPolicy {
    public long fee(long subtotal, long net) {
        Money.nonNegative(subtotal);
        Money.nonNegative(net);
        return subtotal >= 5000 ? 0 : 600;
    }
}
