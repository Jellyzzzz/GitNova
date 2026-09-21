package orderflow.pricing;

import orderflow.domain.Money;

public final class PricingPolicy {
    public long discount(long subtotal, int percent) {
        Money.nonNegative(subtotal);
        Money.percentage(percent);
        long whole = Math.multiplyExact(subtotal / 100, percent);
        long remainder = ((subtotal % 100) * percent + 50) / 100;
        return Math.addExact(whole, remainder);
    }
}
