package eventstats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static testing.Checks.*;

public final class PublicChecks {
    public static void main(String[] args) {
        test("parse/offset", () -> equal(0L, new EventParser().parse("shop|a|1970-01-01T08:00:00+08:00|3").timestamp()));
        test("parse/invalid-row", () -> rejects(() -> new EventParser().parse("shop|a|3")));
        test("dedup/tenants", () -> {
            EventStore store = new EventStore();
            equal(true, store.accept(new Event("a", "same", 0, 3)));
            equal(true, store.accept(new Event("b", "same", 0, 3)));
            equal(2, store.all().size());
        });
        test("dedup/replay", () -> {
            EventStore store = new EventStore();
            Event event = new Event("a", "x", 0, 3);
            equal(true, store.accept(event));
            equal(false, store.accept(event));
        });
        test("windows/negative-epoch", () -> equal(Map.of(-100L, 7L),
                new WindowAggregator().totals(List.of(new Event("a", "x", -1, 7)), "a", -100, 100, 100)));
        test("percentile/rank", () -> equal(40L,
                new Percentiles().nearestRank(new ArrayList<>(List.of(10L, 20L, 30L, 40L)), 90)));
        test("cursor/ties", () -> {
            List<Event> events = List.of(new Event("a", "1", 5, 1), new Event("a", "2", 5, 2));
            equal(List.of(events.get(1)), new CursorPager().page(events, events.get(0), 1));
        });
        test("report/integration", () -> {
            ReportService service = new ReportService();
            service.ingest("a|1|1970-01-01T00:00:00Z|10\na|2|1970-01-01T00:00:01Z|20");
            equal(Map.of(0L, 30L), service.totals("a", 0, 2000, 2000));
            equal(2, service.page(null, 10).size());
        });
        finish("PUBLIC");
    }
}
