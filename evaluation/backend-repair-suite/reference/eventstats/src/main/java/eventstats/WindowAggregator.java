package eventstats;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class WindowAggregator {
    public Map<Long, Long> totals(List<Event> events, String tenant, long start, long end, long width) {
        if (width <= 0 || start > end) throw new IllegalArgumentException("invalid window");
        Map<Long, Long> totals = new TreeMap<>();
        for (Event event : events) {
            if (!event.tenant().equals(tenant) || event.timestamp() < start || event.timestamp() >= end) continue;
            long bucket = Math.floorDiv(event.timestamp(), width) * width;
            totals.merge(bucket, event.value(), Math::addExact);
        }
        return totals;
    }
}
