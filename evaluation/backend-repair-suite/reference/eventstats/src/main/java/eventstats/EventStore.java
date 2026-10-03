package eventstats;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EventStore {
    private record Key(String tenant, String id) {}
    private final Map<Key, Event> events = new LinkedHashMap<>();

    public boolean accept(Event event) {
        Key key = new Key(event.tenant(), event.id());
        Event previous = events.get(key);
        if (previous != null) {
            if (!previous.equals(event)) throw new IllegalArgumentException("conflicting replay");
            return false;
        }
        events.put(key, event);
        return true;
    }

    public List<Event> all() { return List.copyOf(events.values()); }
}
