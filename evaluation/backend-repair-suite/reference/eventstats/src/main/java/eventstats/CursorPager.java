package eventstats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class CursorPager {
    public static final Comparator<Event> ORDER = Comparator.comparingLong(Event::timestamp)
            .thenComparing(Event::tenant).thenComparing(Event::id);

    public List<Event> page(List<Event> events, Event cursor, int limit) {
        if (limit <= 0 || limit > 100) throw new IllegalArgumentException("invalid limit");
        List<Event> sorted = new ArrayList<>(events);
        sorted.sort(ORDER);
        List<Event> result = new ArrayList<>();
        for (Event event : sorted) {
            if (cursor != null && ORDER.compare(event, cursor) <= 0) continue;
            result.add(event);
            if (result.size() == limit) break;
        }
        return result;
    }
}
