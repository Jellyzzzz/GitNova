package orderflow;

import orderflow.domain.OrderLine;
import orderflow.domain.PlaceOrder;
import java.util.List;

public final class IdempotencyChecks {
    public static int run() {
        var t = new TestSupport("idempotency");
        t.check("exact-replay-no-effects", "true/1/1/999", () -> {
            var f = TestSupport.fixture();
            var request = TestSupport.request("same", "alice", 0, new OrderLine("PEN", 1));
            f.requests.submit(request);
            var replay = f.requests.submit(request);
            return replay.replayed() + "/" + f.orders.size() + "/" + f.events.size() + "/" + f.inventory.available("PEN");
        });
        t.check("reordered-replays", true, () -> {
            var f = TestSupport.fixture();
            var first = f.requests.submit(TestSupport.request("same", "alice", 0,
                    new OrderLine("PEN", 1), new OrderLine("BOOK", 1)));
            var second = f.requests.submit(TestSupport.request("same", "alice", 0,
                    new OrderLine("BOOK", 1), new OrderLine("PEN", 1)));
            return second.replayed() && first.orderId().equals(second.orderId()) && f.events.size() == 1;
        });
        t.check("split-lines-replay", true, () -> {
            var f = TestSupport.fixture();
            f.requests.submit(TestSupport.request("same", "alice", 0, new OrderLine("PEN", 3)));
            return f.requests.submit(TestSupport.request("same", "alice", 0,
                    new OrderLine("PEN", 1), new OrderLine("PEN", 2))).replayed();
        });
        t.check("discount-conflict", true, () -> {
            var f = TestSupport.fixture();
            f.requests.submit(TestSupport.request("same", "alice", 0, new OrderLine("PEN", 1)));
            return TestSupport.rejected(() -> f.requests.submit(
                    TestSupport.request("same", "alice", 50, new OrderLine("PEN", 1)))) && f.orders.size() == 1;
        });
        t.check("destination-conflict", true, () -> {
            var f = TestSupport.fixture();
            f.requests.submit(TestSupport.request("same", "alice", 0, new OrderLine("PEN", 1)));
            return TestSupport.rejected(() -> f.requests.submit(new PlaceOrder("same", "alice", "west",
                    List.of(new OrderLine("PEN", 1)), 0))) && f.events.size() == 1;
        });
        t.check("quantity-conflict", true, () -> {
            var f = TestSupport.fixture();
            f.requests.submit(TestSupport.request("same", "alice", 0, new OrderLine("PEN", 1)));
            return TestSupport.rejected(() -> f.requests.submit(
                    TestSupport.request("same", "alice", 0, new OrderLine("PEN", 2)))) && f.inventory.available("PEN") == 999;
        });
        t.check("actors-isolated", 2, () -> {
            var f = TestSupport.fixture();
            f.requests.submit(TestSupport.request("same", "alice", 0, new OrderLine("PEN", 1)));
            f.requests.submit(TestSupport.request("same", "bob", 0, new OrderLine("PEN", 1)));
            return f.orders.size();
        });
        t.check("replay-after-cancel", "true/1/1000", () -> {
            var f = TestSupport.fixture();
            var request = TestSupport.request("same", "alice", 0, new OrderLine("PEN", 1));
            var receipt = f.requests.submit(request);
            f.cancellation.cancel("alice", receipt.orderId());
            var replay = f.requests.submit(request);
            return replay.replayed() + "/" + f.orders.size() + "/" + f.inventory.available("PEN");
        });
        return t.finish();
    }
}
