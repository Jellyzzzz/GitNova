package pricing;

public final class PricingService {
    private final DiscountPolicy discountPolicy = new DiscountPolicy();
    private final ShippingPolicy shippingPolicy = new ShippingPolicy();

    public Quote quote(long subtotalCents, int discountPercent) {
        if (subtotalCents < 0 || subtotalCents > 1_000_000
                || discountPercent < 0 || discountPercent > 100) {
            throw new IllegalArgumentException("Invalid order amount or discount");
        }
        long discount = discountPolicy.discountCents(subtotalCents, discountPercent);
        long shipping = shippingPolicy.shippingCents(subtotalCents, discount);
        return new Quote(discount, shipping, subtotalCents - discount + shipping);
    }

    public record Quote(long discountCents, long shippingCents, long totalCents) {}
}
