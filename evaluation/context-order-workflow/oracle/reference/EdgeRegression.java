package orderflow;

import orderflow.domain.OrderLine;
import orderflow.domain.PlaceOrder;
import java.util.List;

/** Verifier self-test only. Never copied into the model's initial repository. */
public final class EdgeRegression {
    private static int checks;
    private static int failures;

    private static void check(String id, boolean value) {
        checks++;
        if (!value) { failures++; System.out.println("FAIL " + id); }
    }

    public static void main(String[] args) {
        for (int q = 1; q <= 4; q++) {
            final int quantity = 2 * q + 1;
            var f = TestSupport.fixture();
            var r = f.requests.submit(TestSupport.request("p", "alice", 50, new OrderLine("PEN", 2 * q + 1)));
            check("round-" + q, r.quote().discount() == (101L * (2 * q + 1) + 1) / 2);
            var shipping = f.pricing.quote(TestSupport.request("s", "alice", 10, new OrderLine("CASE", 1)));
            check("net-shipping-" + q, shipping.shipping() == 600);
            f.service.pay("alice", r.orderId());
            int stock = f.inventory.available("PEN");
            check("paid-rejected-" + q, TestSupport.rejected(() -> f.cancellation.cancel("alice", r.orderId())));
            check("paid-stock-" + q, stock == f.inventory.available("PEN"));
            check("paid-events-" + q, f.events.size() == 2);
            check("discount-conflict-" + q, TestSupport.rejected(() -> f.requests.submit(
                    TestSupport.request("p", "alice", 10, new OrderLine("PEN", quantity)))));
        }
        var f = TestSupport.fixture();
        var request = TestSupport.request("c", "alice", 0, new OrderLine("PEN", 3));
        var r = f.requests.submit(request);
        f.cancellation.cancel("alice", r.orderId());
        f.cancellation.cancel("alice", r.orderId());
        check("cancel-event-once", f.events.size() == 2);
        check("cancel-stock", f.inventory.available("PEN") == 1000);
        check("historical-retry", f.requests.submit(request).replayed());
        check("address-conflict", TestSupport.rejected(() -> f.requests.submit(
                new PlaceOrder("c", "alice", "west", List.of(new OrderLine("PEN", 3)), 0))));
        var batch = TestSupport.fixture();
        var result = batch.importer.importRows("b", List.of("a,alice,PEN,1,0,east", "bad", "z,alice,PEN,1,0,east"));
        check("batch-all-rows", result.rows().size() == 3);
        check("batch-tail", result.accepted() == 2);
        check("batch-no-rollback", batch.orders.size() == 2);
        System.out.println("EDGE SUMMARY checks=" + checks + " failed=" + failures);
        if (failures != 0) System.exit(1);
    }
}
