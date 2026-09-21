package orderflow;

import orderflow.domain.OrderLine;
import java.util.List;

public final class WorkflowChecks {
    public static int run() {
        var t = new TestSupport("workflow");
        t.check("mixed-order-price-and-cancel", "111/995/600/1595/300/1000", () -> {
            var f = TestSupport.fixture();
            var r = f.requests.submit(TestSupport.request("x", "alice", 10,
                    new OrderLine("BOOK", 1), new OrderLine("PEN", 1)));
            f.cancellation.cancel("alice", r.orderId());
            var q = r.quote();
            return q.discount() + "/" + q.net() + "/" + q.shipping() + "/" + q.total()
                    + "/" + f.inventory.available("BOOK") + "/" + f.inventory.available("PEN");
        });
        t.check("pay-retry-and-denied-cancel", "1/2/PAID/998", () -> {
            var f = TestSupport.fixture();
            var r = f.requests.submit(TestSupport.request("x", "alice", 0, new OrderLine("PEN", 2)));
            f.service.pay("alice", r.orderId());
            f.service.pay("alice", r.orderId());
            TestSupport.rejected(() -> f.cancellation.cancel("alice", r.orderId()));
            return f.orders.size() + "/" + f.events.size() + "/" + f.query.view("alice", r.orderId()).status()
                    + "/" + f.inventory.available("PEN");
        });
        t.check("batch-conflict-followed-by-valid", "3/1/3/998", () -> {
            var f = TestSupport.fixture();
            var r = f.importer.importRows("b", List.of("x,alice,PEN,1,0,east",
                    "x,alice,PEN,1,50,east", "y,alice,PEN,1,0,east"));
            return r.rows().size() + "/" + r.rejected() + "/" + r.rows().get(2).line()
                    + "/" + f.inventory.available("PEN");
        });
        t.check("cancel-and-reordered-retry", "true/2/1000", () -> {
            var f = TestSupport.fixture();
            var first = f.requests.submit(TestSupport.request("x", "alice", 0,
                    new OrderLine("PEN", 1), new OrderLine("BOOK", 1)));
            f.cancellation.cancel("alice", first.orderId());
            var second = f.requests.submit(TestSupport.request("x", "alice", 0,
                    new OrderLine("BOOK", 1), new OrderLine("PEN", 1)));
            return second.replayed() + "/" + f.events.size() + "/" + f.inventory.available("PEN");
        });
        return t.finish();
    }
}
