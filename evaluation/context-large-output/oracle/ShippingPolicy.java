package pricing;

public final class ShippingPolicy {
    public long shippingCents(long subtotalCents, long discountCents) {
        return subtotalCents - discountCents >= 5_000 ? 0 : 600;
    }
}
