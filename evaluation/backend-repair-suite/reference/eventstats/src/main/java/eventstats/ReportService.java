package eventstats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ReportService {
    private final EventStore store = new EventStore();

    public void ingest(String text) {
        for (String line : text.split("\\R")) {
            if (!line.isBlank() && !line.startsWith("#")) store.accept(new EventParser().parse(line));
        }
    }

    public Map<Long, Long> totals(String tenant, long start, long end, long width) {
        return new WindowAggregator().totals(store.all(), tenant, start, end, width);
    }

    public long percentile(String tenant, int percentile) {
        List<Long> values = new ArrayList<>();
        for (Event event : store.all()) if (event.tenant().equals(tenant)) values.add(event.value());
        return new Percentiles().nearestRank(values, percentile);
    }

    public List<Event> page(Event after, int limit) { return new CursorPager().page(store.all(), after, limit); }
}
