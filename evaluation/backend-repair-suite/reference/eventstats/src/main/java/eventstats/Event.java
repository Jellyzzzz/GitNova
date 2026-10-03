package eventstats;

public record Event(String tenant, String id, long timestamp, long value) {
    public Event {
        if (tenant == null || tenant.isBlank() || id == null || id.isBlank() || value < 0) {
            throw new IllegalArgumentException("invalid event");
        }
    }
}
