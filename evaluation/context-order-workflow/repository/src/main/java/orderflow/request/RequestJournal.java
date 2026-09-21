package orderflow.request;

import orderflow.domain.OrderReceipt;
import java.util.HashMap;
import java.util.Map;

public final class RequestJournal {
    public record Key(String actorId, String requestId) {}
    public record Entry(String fingerprint, OrderReceipt receipt) {}
    private final Map<Key, Entry> entries = new HashMap<>();

    public Entry find(String actorId, String requestId) { return entries.get(new Key(actorId, requestId)); }

    public void save(String actorId, String requestId, String fingerprint, OrderReceipt receipt) {
        if (entries.putIfAbsent(new Key(actorId, requestId), new Entry(fingerprint, receipt)) != null) {
            throw new IllegalStateException("request already recorded");
        }
    }

    public int size() { return entries.size(); }
}
