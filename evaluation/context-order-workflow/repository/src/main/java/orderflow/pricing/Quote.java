package orderflow.pricing;

import orderflow.domain.Money;

public record Quote(long subtotal, long discount, long net, long shipping, long total) {
    public Quote {
        Money.nonNegative(subtotal);
        Money.nonNegative(discount);
        Money.nonNegative(net);
        Money.nonNegative(shipping);
        if (Money.add(discount, net) != subtotal || Money.add(net, shipping) != total) {
            throw new IllegalArgumentException("inconsistent quote");
        }
    }
}
