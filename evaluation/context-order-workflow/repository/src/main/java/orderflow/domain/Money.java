package orderflow.domain;

/** All amounts are integer cents. Overflow is a rejected operation, never wraparound. */
public final class Money {
    private Money() {}

    public static long nonNegative(long cents) {
        if (cents < 0) throw new IllegalArgumentException("negative amount");
        return cents;
    }

    public static long add(long a, long b) {
        return Math.addExact(nonNegative(a), nonNegative(b));
    }

    public static long multiply(long price, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        return Math.multiplyExact(nonNegative(price), quantity);
    }

    public static int percentage(int percent) {
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("invalid percent");
        return percent;
    }
}
