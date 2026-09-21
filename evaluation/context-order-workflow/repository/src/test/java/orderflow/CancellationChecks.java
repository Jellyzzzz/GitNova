package orderflow;

import orderflow.domain.OrderLine;

public final class CancellationChecks {
    public static int run() {
        var t = new TestSupport("cancellation");
        t.check("reserved-cancel", "true/CANCELLED/1/1000/2", () -> {
            var f = TestSupport.fixture();
            var r = f.requests.submit(TestSupport.request("c", "alice", 0, new OrderLine("PEN", 5)));
            var cancel = f.cancellation.cancel("alice", r.orderId());
            return cancel.changed() + "/" + cancel.status() + "/" + cancel.version()
                    + "/" + f.inventory.available("PEN") + "/" + f.events.size();
        });
        t.check("repeat-no-event", 2, () -> {
            var f = TestSupport.fixture();
            var r = f.requests.submit(TestSupport.request("c", "alice", 0, new OrderLine("PEN", 5)));
            f.cancellation.cancel("alice", r.orderId());
            f.cancellation.cancel("alice", r.orderId());
            return f.events.size();
        });
        t.check("repeat-receipt", "false/1", () -> {
            var f = TestSupport.fixture();
            var r = f.requests.submit(TestSupport.request("c", "alice", 0, new OrderLine("PEN", 1)));
            f.cancellation.cancel("alice", r.orderId());
            var second = f.cancellation.cancel("alice", r.orderId());
            return second.changed() + "/" + second.version();
        });
        t.check("paid-reject-keeps-stock", 995, () -> {
            var f = TestSupport.fixture();
            var r = f.requests.submit(TestSupport.request("c", "alice", 0, new OrderLine("PEN", 5)));
            f.service.pay("alice", r.orderId());
            if (!TestSupport.rejected(() -> f.cancellation.cancel("alice", r.orderId()))) return -1;
            return f.inventory.available("PEN");
        });
        t.check("paid-reject-keeps-reservation", false, () -> {
            var f = TestSupport.fixture();
            var r = f.requests.submit(TestSupport.request("c", "alice", 0, new OrderLine("PEN", 5)));
            f.service.pay("alice", r.orderId());
            TestSupport.rejected(() -> f.cancellation.cancel("alice", r.orderId()));
            return f.inventory.released(r.orderId());
        });
        t.check("paid-reject-keeps-status", "PAID/1/2", () -> {
            var f = TestSupport.fixture();
            var r = f.requests.submit(TestSupport.request("c", "alice", 0, new OrderLine("PEN", 5)));
            f.service.pay("alice", r.orderId());
            TestSupport.rejected(() -> f.cancellation.cancel("alice", r.orderId()));
            var view = f.query.view("alice", r.orderId());
            return view.status() + "/" + view.version() + "/" + view.events().size();
        });
        t.check("wrong-user-no-effect", true, () -> {
            var f = TestSupport.fixture();
            var r = f.requests.submit(TestSupport.request("c", "alice", 0, new OrderLine("PEN", 1)));
            return TestSupport.rejected(() -> f.cancellation.cancel("bob", r.orderId()))
                    && f.inventory.available("PEN") == 999 && f.events.size() == 1;
        });
        t.check("unknown-no-effect", true, () -> {
            var f = TestSupport.fixture();
            return TestSupport.rejected(() -> f.cancellation.cancel("alice", "missing"))
                    && f.inventory.available("PEN") == 1000 && f.events.size() == 0;
        });
        return t.finish();
    }
}
