package pricing;

public final class DiscountPolicy {
    public long discountCents(long subtotalCents, int discountPercent) {
        return subtotalCents * discountPercent / 100;
    }
}
