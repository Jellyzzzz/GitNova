package orderflow.pricing;

import orderflow.catalog.Catalog;
import orderflow.domain.Money;
import orderflow.domain.PlaceOrder;

public final class PricingService {
    private final Catalog catalog;
    private final PricingPolicy pricing;
    private final ShippingPolicy shipping;

    public PricingService(Catalog catalog, PricingPolicy pricing, ShippingPolicy shipping) {
        this.catalog = catalog;
        this.pricing = pricing;
        this.shipping = shipping;
    }

    public Quote quote(PlaceOrder request) {
        long subtotal = 0;
        for (var line : request.lines()) {
            long amount = Money.multiply(catalog.require(line.sku()).unitCents(), line.quantity());
            subtotal = Money.add(subtotal, amount);
        }
        long discount = pricing.discount(subtotal, request.percent());
        long net = subtotal - discount;
        long fee = shipping.fee(subtotal, net);
        return new Quote(subtotal, discount, net, fee, Money.add(net, fee));
    }
}
