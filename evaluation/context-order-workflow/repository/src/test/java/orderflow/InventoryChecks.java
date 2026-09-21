package orderflow;

import orderflow.domain.OrderLine;
import java.util.List;

public final class InventoryChecks {
    public static int run() {
        var t = new TestSupport("inventory");
        t.check("reserve-two-skus", "998/299", () -> {
            var f = TestSupport.fixture();
            f.inventory.reserve("x", List.of(new OrderLine("PEN", 2), new OrderLine("BOOK", 1)));
            return f.inventory.available("PEN") + "/" + f.inventory.available("BOOK");
        });
        t.check("aggregate-duplicates", 995, () -> {
            var f = TestSupport.fixture();
            f.inventory.reserve("x", List.of(new OrderLine("PEN", 2), new OrderLine("PEN", 3)));
            return f.inventory.available("PEN");
        });
        t.check("insufficient-zero-effect", true, () -> {
            var f = TestSupport.fixture();
            var before = f.inventory.snapshot();
            boolean rejected = TestSupport.rejected(() -> f.inventory.reserve("x",
                    List.of(new OrderLine("PEN", 1), new OrderLine("BAG", 101))));
            return rejected && before.equals(f.inventory.snapshot()) && f.inventory.reservationCount() == 0;
        });
        t.check("unknown-zero-effect", true, () -> {
            var f = TestSupport.fixture();
            var before = f.inventory.snapshot();
            return TestSupport.rejected(() -> f.inventory.reserve("x",
                    List.of(new OrderLine("PEN", 1), new OrderLine("UNKNOWN", 1))))
                    && before.equals(f.inventory.snapshot());
        });
        t.check("release-once", "true/false/1000", () -> {
            var f = TestSupport.fixture();
            f.inventory.reserve("x", List.of(new OrderLine("PEN", 7)));
            return f.inventory.release("x") + "/" + f.inventory.release("x") + "/" + f.inventory.available("PEN");
        });
        t.check("duplicate-reservation", true, () -> {
            var f = TestSupport.fixture();
            f.inventory.reserve("x", List.of(new OrderLine("PEN", 1)));
            return TestSupport.rejected(() -> f.inventory.reserve("x", List.of(new OrderLine("PEN", 1))))
                    && f.inventory.available("PEN") == 999;
        });
        t.check("aggregate-overflow", true, () -> {
            var f = TestSupport.fixture();
            return TestSupport.rejected(() -> f.inventory.reserve("x",
                    List.of(new OrderLine("PEN", Integer.MAX_VALUE), new OrderLine("PEN", 1))))
                    && f.inventory.available("PEN") == 1000;
        });
        return t.finish();
    }
}
