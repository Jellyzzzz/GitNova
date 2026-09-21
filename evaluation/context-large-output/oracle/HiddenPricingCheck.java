package pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Held out: never materialize this file in the model's Workspace. */
public final class HiddenPricingCheck {
    public static void main(String[] args) {
        PricingService service = new PricingService();
        long[] subtotals = {0, 1, 5, 49, 50, 51, 99, 100, 101, 1005, 4999, 5000,
                5001, 5050, 5200, 5505, 5555, 5556, 6250, 9999, 1_000_000};
        int checked = 0;
        int failed = 0;
        for (long subtotal : subtotals) {
            for (int percent = 0; percent <= 100; percent++) {
                long discount = BigDecimal.valueOf(subtotal)
                        .multiply(BigDecimal.valueOf(percent))
                        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                        .longValueExact();
                long shipping = subtotal - discount >= 5000 ? 0 : 600;
                var quote = service.quote(subtotal, percent);
                checked++;
                if (quote.discountCents() != discount || quote.shippingCents() != shipping
                        || quote.totalCents() != subtotal - discount + shipping) {
                    if (failed < 5) {
                        System.out.printf("ORACLE FAIL subtotal=%d percent=%d expected=(%d,%d,%d) actual=%s%n",
                                subtotal, percent, discount, shipping, subtotal - discount + shipping, quote);
                    }
                    failed++;
                }
            }
        }
        long[][] invalid = {{-1, 0}, {1_000_001, 0}, {100, -1}, {100, 101}};
        for (long[] input : invalid) {
            checked++;
            try {
                service.quote(input[0], (int) input[1]);
                failed++;
            } catch (IllegalArgumentException expected) {
                // The existing public contract must still hold.
            }
        }
        System.out.printf("ORACLE SUMMARY cases=%d failed=%d%n", checked, failed);
        if (failed > 0) System.exit(1);
    }
}
