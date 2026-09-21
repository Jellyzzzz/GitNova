package orderflow;

import orderflow.catalog.Catalog;
import orderflow.catalog.Product;
import orderflow.domain.OrderLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class PricingChecks {
    public static int run() {
        var t = new TestSupport("pricing");
        for (long amount : new long[]{1, 101, 1005, 4999, 5000, 5200, 5505, 5556, 9999}) {
            for (int percent : new int[]{0, 10, 50, 100}) {
                long discount = BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(percent))
                        .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact();
                long net = amount - discount;
                long shipping = net >= 5000 ? 0 : 600;
                String expected = discount + "/" + net + "/" + shipping + "/" + (net + shipping);
                t.check(amount + "x" + percent, expected, () -> {
                    var f = new Application(new Catalog(List.of(new Product("ITEM", amount, 10))), () -> 0L);
                    var q = f.pricing.quote(TestSupport.request("p", "alice", percent, new OrderLine("ITEM", 1)));
                    return q.discount() + "/" + q.net() + "/" + q.shipping() + "/" + q.total();
                });
            }
        }
        t.check("aggregate-before-round", 101L, () -> {
            var f = TestSupport.fixture();
            return f.pricing.quote(TestSupport.request("p", "alice", 50,
                    new OrderLine("PEN", 1), new OrderLine("PEN", 1))).discount();
        });
        t.check("mixed-lines", 111L, () -> TestSupport.fixture().pricing.quote(
                TestSupport.request("p", "alice", 10, new OrderLine("PEN", 1), new OrderLine("BOOK", 1))).discount());
        return t.finish();
    }
}
