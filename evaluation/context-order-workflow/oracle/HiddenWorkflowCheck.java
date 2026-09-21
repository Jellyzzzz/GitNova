package orderflow.oracle;

import orderflow.Application;
import orderflow.catalog.Catalog;
import orderflow.catalog.Product;
import orderflow.domain.OrderLine;
import orderflow.domain.OrderStatus;
import orderflow.domain.PlaceOrder;
import orderflow.batch.ImportResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/** Independent executable specification. No reference sources or public test helpers are used. */
public final class HiddenWorkflowCheck {
    private static final Map<String, int[]> results = new LinkedHashMap<>();

    private static Application fixture() {
        return new Application(new Catalog(List.of(new Product("AAA", 103, 10000),
                new Product("BBB", 1007, 10000), new Product("CCC", 5011, 100))), () -> 99L);
    }

    private static PlaceOrder request(String key, String actor, String destination, int percent, OrderLine... lines) {
        return new PlaceOrder(key, actor, destination, List.of(lines), percent);
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected=" + expected + " actual=" + actual);
    }

    private static void rejects(Runnable operation) {
        try { operation.run(); }
        catch (IllegalArgumentException | IllegalStateException | ArithmeticException rejected) { return; }
        throw new AssertionError("expected rejection");
    }

    private static void test(String suite, String id, Runnable check) {
        int[] counts = results.computeIfAbsent(suite, ignored -> new int[2]);
        counts[0]++;
        try { check.run(); }
        catch (RuntimeException | AssertionError failure) {
            counts[1]++;
            if (counts[1] <= 8) System.out.println("ORACLE FAIL " + suite + "/" + id + " " + failure);
        }
    }

    private static void contract() {
        test("contract", "bad-request", () -> {
            rejects(() -> request("bad key", "alice", "east", 0, new OrderLine("AAA", 1)));
            rejects(() -> request("key", "alice", "east", -1, new OrderLine("AAA", 1)));
            rejects(() -> new OrderLine("AAA", 0));
        });
        test("contract", "query-and-event-snapshots", () -> {
            var f = fixture();
            var r = f.requests.submit(request("x", "alice", "east", 0, new OrderLine("AAA", 1)));
            var snapshot = f.query.view("alice", r.orderId());
            f.service.pay("alice", r.orderId());
            equal(OrderStatus.RESERVED, snapshot.status());
            equal(1, snapshot.events().size());
            rejects(() -> f.query.view("bob", r.orderId()));
            equal(0, f.query.all("bob").size());
        });
    }

    private static void inventory() {
        for (int quantity = 1; quantity <= 40; quantity++) {
            final int q = quantity;
            test("inventory", "atomic-" + q, () -> {
                var f = fixture();
                var original = f.inventory.snapshot();
                rejects(() -> f.inventory.reserve("bad", List.of(new OrderLine("AAA", q), new OrderLine("CCC", 101))));
                equal(original, f.inventory.snapshot());
                equal(0, f.inventory.reservationCount());
                f.inventory.reserve("good", List.of(new OrderLine("AAA", q), new OrderLine("AAA", q)));
                equal(10000 - 2 * q, f.inventory.available("AAA"));
                equal(true, f.inventory.release("good"));
                equal(false, f.inventory.release("good"));
                equal(original, f.inventory.snapshot());
            });
        }
    }

    private static void pricing() {
        Random random = new Random(20260921);
        List<Long> amounts = new ArrayList<>(List.of(0L, 1L, 49L, 50L, 51L, 4999L, 5000L, 5001L, 9999L));
        for (int i = 0; i < 80; i++) amounts.add((long) random.nextInt(20000));
        for (long amount : amounts) {
            for (int pct : new int[]{0, 1, 7, 10, 33, 50, 67, 99, 100}) {
                test("pricing", amount + "-" + pct, () -> {
                    var f = new Application(new Catalog(List.of(new Product("XXX", amount, 100))), () -> 0L);
                    var quote = f.pricing.quote(request("price", "alice", "north", pct, new OrderLine("XXX", 1)));
                    long discount = BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(pct))
                            .movePointLeft(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
                    long net = amount - discount;
                    long fee = net < 5000 ? 600 : 0;
                    equal(amount, quote.subtotal());
                    equal(discount, quote.discount());
                    equal(net, quote.net());
                    equal(fee, quote.shipping());
                    equal(net + fee, quote.total());
                });
            }
        }
        for (int q = 1; q <= 31; q++) {
            final int quantity = q;
            test("pricing", "aggregate-" + q, () -> {
                var f = fixture();
                var split = f.pricing.quote(request("x", "alice", "east", 50,
                        new OrderLine("AAA", quantity), new OrderLine("AAA", 1)));
                var combined = f.pricing.quote(request("y", "alice", "east", 50,
                        new OrderLine("AAA", quantity + 1)));
                equal(combined, split);
                long expected = BigDecimal.valueOf(103L * (quantity + 1)).divide(BigDecimal.valueOf(2),
                        0, RoundingMode.HALF_UP).longValueExact();
                equal(expected, split.discount());
            });
        }
    }

    private static void cancellation() {
        for (int q = 1; q <= 32; q++) {
            final int quantity = q;
            test("cancellation", "reserved-" + q, () -> {
                var f = fixture();
                var r = f.requests.submit(request("cancel", "alice", "east", 0, new OrderLine("AAA", quantity)));
                equal(true, f.cancellation.cancel("alice", r.orderId()).changed());
                var repeat = f.cancellation.cancel("alice", r.orderId());
                equal(false, repeat.changed());
                equal(1L, repeat.version());
                equal(OrderStatus.CANCELLED, repeat.status());
                equal(10000, f.inventory.available("AAA"));
                equal(2, f.events.size());
            });
            test("cancellation", "paid-" + q, () -> {
                var f = fixture();
                var r = f.requests.submit(request("paid", "alice", "east", 0, new OrderLine("AAA", quantity)));
                f.service.pay("alice", r.orderId());
                var inventory = f.inventory.snapshot();
                var view = f.query.view("alice", r.orderId());
                rejects(() -> f.cancellation.cancel("alice", r.orderId()));
                equal(inventory, f.inventory.snapshot());
                equal(view, f.query.view("alice", r.orderId()));
                equal(false, f.inventory.released(r.orderId()));
            });
        }
        test("cancellation", "unauthorized", () -> {
            var f = fixture();
            var r = f.requests.submit(request("x", "alice", "east", 0, new OrderLine("AAA", 1)));
            rejects(() -> f.cancellation.cancel("bob", r.orderId()));
            rejects(() -> f.cancellation.cancel("alice", "missing"));
            equal(9999, f.inventory.available("AAA"));
            equal(1, f.events.size());
        });
    }

    private static void idempotency() {
        for (int q = 1; q <= 24; q++) {
            final int quantity = q;
            test("idempotency", "equivalent-" + q, () -> {
                var f = fixture();
                var r = f.requests.submit(request("key", "alice", "east", 10,
                        new OrderLine("AAA", quantity + 1), new OrderLine("BBB", 2)));
                var repeat = f.requests.submit(request("key", "alice", "east", 10,
                        new OrderLine("BBB", 1), new OrderLine("AAA", quantity),
                        new OrderLine("BBB", 1), new OrderLine("AAA", 1)));
                equal(r.orderId(), repeat.orderId());
                equal(true, repeat.replayed());
                equal(r.quote(), repeat.quote());
                equal(1, f.orders.size());
                equal(1, f.events.size());
                equal(10000 - quantity - 1, f.inventory.available("AAA"));
            });
            test("idempotency", "conflicts-" + q, () -> {
                var f = fixture();
                f.requests.submit(request("key", "alice", "east", 10, new OrderLine("AAA", quantity)));
                var stock = f.inventory.snapshot();
                rejects(() -> f.requests.submit(request("key", "alice", "west", 10, new OrderLine("AAA", quantity))));
                rejects(() -> f.requests.submit(request("key", "alice", "east", 11, new OrderLine("AAA", quantity))));
                rejects(() -> f.requests.submit(request("key", "alice", "east", 10, new OrderLine("AAA", quantity + 1))));
                equal(stock, f.inventory.snapshot());
                equal(1, f.orders.size());
                equal(1, f.events.size());
            });
        }
        test("idempotency", "actor-and-cancel", () -> {
            var f = fixture();
            var request = request("shared", "alice", "east", 0, new OrderLine("AAA", 1));
            var a = f.requests.submit(request);
            var b = f.requests.submit(request("shared", "bob", "east", 0, new OrderLine("AAA", 1)));
            equal(false, a.orderId().equals(b.orderId()));
            f.cancellation.cancel("alice", a.orderId());
            equal(a.orderId(), f.requests.submit(request).orderId());
            equal(2, f.orders.size());
            equal(9999, f.inventory.available("AAA"));
        });
        test("idempotency", "failed-not-recorded", () -> {
            var f = fixture();
            rejects(() -> f.requests.submit(request("retry", "alice", "east", 0, new OrderLine("CCC", 101))));
            equal(0, f.journal.size());
            equal(false, f.requests.submit(request("retry", "alice", "east", 0, new OrderLine("CCC", 1))).replayed());
        });
    }

    private static void batch() {
        for (int count = 1; count <= 36; count++) {
            final int n = count;
            test("batch", "mixed-" + n, () -> {
                var f = fixture();
                List<String> rows = new ArrayList<>();
                int valid = 0;
                for (int i = 0; i < n; i++) {
                    boolean bad = i % 3 == 0;
                    rows.add(bad ? "bad-" + i : "k" + i + ",alice,AAA,1,0,east");
                    if (!bad) valid++;
                }
                var r = f.importer.importRows("batch", rows);
                equal(n, r.rows().size());
                equal((long) valid, r.accepted());
                equal((long) (n - valid), r.rejected());
                equal(10000 - valid, f.inventory.available("AAA"));
                for (int i = 0; i < n; i++) {
                    equal(i + 1, r.rows().get(i).line());
                    equal(i % 3 == 0 ? ImportResult.Status.REJECTED : ImportResult.Status.ACCEPTED,
                            r.rows().get(i).status());
                }
            });
        }
        test("batch", "conflict-and-retry", () -> {
            var f = fixture();
            var r = f.importer.importRows("batch", List.of("x,alice,AAA,1,0,east",
                    "x,alice,AAA,1,0,east", "x,alice,AAA,1,50,east", "y,bob,BBB,2,0,west"));
            equal(4, r.rows().size());
            equal(2L, r.accepted());
            equal(1L, r.replayed());
            equal(1L, r.rejected());
            equal(2, f.events.size());
        });
    }

    private static void workflow() {
        for (int q = 1; q <= 20; q++) {
            final int quantity = q;
            test("workflow", "order-lifecycle-" + q, () -> {
                var f = fixture();
                var r = f.requests.submit(request("x", "alice", "east", 50,
                        new OrderLine("AAA", quantity), new OrderLine("BBB", 1)));
                long subtotal = 103L * quantity + 1007;
                long discount = BigDecimal.valueOf(subtotal).divide(BigDecimal.valueOf(2), 0, RoundingMode.HALF_UP).longValueExact();
                equal(discount, r.quote().discount());
                f.cancellation.cancel("alice", r.orderId());
                f.cancellation.cancel("alice", r.orderId());
                equal(2, f.events.size());
                var replay = f.requests.submit(request("x", "alice", "east", 50,
                        new OrderLine("BBB", 1), new OrderLine("AAA", quantity)));
                equal(true, replay.replayed());
                equal(10000, f.inventory.available("AAA"));
                equal(1, f.orders.size());
            });
        }
    }

    public static void main(String[] args) {
        contract();
        inventory();
        pricing();
        cancellation();
        idempotency();
        batch();
        workflow();
        int failures = 0;
        for (var entry : results.entrySet()) {
            System.out.println("ORACLE suite=" + entry.getKey() + " checks=" + entry.getValue()[0] + " failed=" + entry.getValue()[1]);
            failures += entry.getValue()[1];
        }
        System.out.println("ORACLE TOTAL failed=" + failures);
        if (failures != 0) System.exit(1);
    }
}
