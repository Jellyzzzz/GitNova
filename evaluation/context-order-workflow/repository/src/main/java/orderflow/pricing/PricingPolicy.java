package orderflow.pricing;

import orderflow.domain.Money;

public final class PricingPolicy {
    public long discount(long subtotal, int percent) {
        Money.nonNegative(subtotal);
        Money.percentage(percent);
        return Math.multiplyExact(subtotal, percent) / 100;
    }
}
