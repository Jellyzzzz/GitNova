package eventstats;

import java.time.OffsetDateTime;

public final class EventParser {
    public Event parse(String line) {
        String[] fields = line.split("\\|", -1);
        if (fields.length != 4) throw new IllegalArgumentException("expected four fields");
        long timestamp = OffsetDateTime.parse(fields[2].trim()).toInstant().toEpochMilli();
        return new Event(fields[0].trim(), fields[1].trim(), timestamp, Long.parseLong(fields[3].trim()));
    }
}
