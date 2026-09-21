package orderflow;

import orderflow.domain.Money;
import orderflow.domain.OrderLine;
import orderflow.domain.PlaceOrder;
import java.util.ArrayList;
import java.util.List;

public final class ContractChecks {
    public static int run() {
        var t = new TestSupport("contract");
        t.check("negative-amount", true, () -> TestSupport.rejected(() -> Money.nonNegative(-1)));
        t.check("overflow-add", true, () -> TestSupport.rejected(() -> Money.add(Long.MAX_VALUE, 1)));
        t.check("zero-quantity", true, () -> TestSupport.rejected(() -> new OrderLine("PEN", 0)));
        t.check("unknown-sku", true, () -> TestSupport.rejected(() -> TestSupport.fixture().catalog.require("NO")));
        t.check("invalid-percent", true, () -> TestSupport.rejected(() ->
                TestSupport.request("bad", "alice", 101, new OrderLine("PEN", 1))));
        t.check("lines-copy", 1, () -> {
            var lines = new ArrayList<>(List.of(new OrderLine("PEN", 1)));
            var request = new PlaceOrder("copy", "alice", "east", lines, 0);
            lines.add(new OrderLine("BOOK", 1));
            return request.lines().size();
        });
        t.check("stock-snapshot-copy", 1000, () -> {
            var f = TestSupport.fixture();
            var before = f.inventory.snapshot();
            f.requests.submit(TestSupport.request("s", "alice", 0, new OrderLine("PEN", 1)));
            return before.get("PEN");
        });
        t.check("actor-read-isolation", true, () -> {
            var f = TestSupport.fixture();
            var r = f.requests.submit(TestSupport.request("s", "alice", 0, new OrderLine("PEN", 1)));
            return f.query.all("bob").isEmpty() && TestSupport.rejected(() -> f.query.view("bob", r.orderId()));
        });
        return t.finish();
    }
}
