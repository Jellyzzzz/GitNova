package eventstats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static testing.Checks.*;

/** Private positive control. Never copied into the public initial repository. */
public final class RegressionChecks {
    public static void main(String[] args) {
        test("timezone", () -> equal(123L,
                new EventParser().parse("a|x|1970-01-01T05:45:00.123+05:45|7").timestamp()));
        test("tenant-key", () -> {
            EventStore store = new EventStore();
            equal(true, store.accept(new Event("a", "same", 1, 7)));
            equal(true, store.accept(new Event("b", "same", 1, 7)));
            equal(true, store.accept(new Event("ab", "c", 1, 7)));
            equal(true, store.accept(new Event("a", "bc", 1, 7)));
            equal(false, store.accept(new Event("a", "same", 1, 7)));
            rejects(() -> store.accept(new Event("a", "same", 2, 7)));
            equal(4, store.all().size());
        });
        test("negative-window", () -> equal(Map.of(-100L, 7L), new WindowAggregator().totals(
                List.of(new Event("a", "left", -100, 3), new Event("a", "right", -1, 4),
                        new Event("a", "outside", 0, 9)), "a", -100, 0, 100)));
        test("percentile-ownership", () -> {
            List<Long> values = new ArrayList<>(List.of(40L, 10L, 30L, 20L));
            equal(40L, new Percentiles().nearestRank(values, 90));
            equal(List.of(40L, 10L, 30L, 20L), values);
            equal(40L, new Percentiles().nearestRank(List.of(10L, 20L, 30L, 40L), 90));
        });
        test("cursor-tuple", () -> {
            List<Event> events = List.of(new Event("a", "2", 5, 1), new Event("b", "1", 5, 2));
            equal(List.of(events.get(1)), new CursorPager().page(events, events.get(0), 1));
        });
        test("report-integration", () -> {
            ReportService report = new ReportService();
            report.ingest("a|same|1970-01-01T05:45:00+05:45|7\nb|same|1970-01-01T00:00:00Z|9");
            equal(Map.of(0L, 7L), report.totals("a", 0, 1000, 1000));
            equal(7L, report.percentile("a", 90));
            equal(2, report.page(null, 10).size());
        });
        finish("REGRESSION");
    }
}
