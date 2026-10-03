package eventstats;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import static testing.Checks.*;

public final class Oracle {
    public static void main(String[] args) {
        for (int hour = -12; hour <= 14; hour++) {
            final int offset = hour;
            test("parse/timezone/" + hour, () -> {
                Instant instant = Instant.parse("2024-02-29T23:59:59.123Z");
                String encoded = instant.atOffset(ZoneOffset.ofHours(offset)).toString();
                equal(instant.toEpochMilli(), new EventParser().parse("tenant|id|" + encoded + "|12").timestamp());
            });
        }
        for (String offset : new String[]{"+05:30", "+05:45", "-03:30", "+09:30"}) {
            test("parse/fractional-offset/" + offset, () -> {
                Instant instant = Instant.parse("2024-02-29T23:59:59.123Z");
                String encoded = instant.atOffset(ZoneOffset.of(offset)).toString();
                equal(instant.toEpochMilli(), new EventParser().parse("tenant|id|" + encoded + "|12").timestamp());
            });
        }
        test("dedup/ambiguous-concatenation", () -> {
            EventStore store = new EventStore();
            for (Event event : List.of(new Event("ab", "c", 0, 2), new Event("a", "bc", 0, 2),
                    new Event("x:y", "z", 0, 2), new Event("x", "y:z", 0, 2))) equal(true, store.accept(event));
            equal(4, store.all().size());
        });
        for (int sample = 1; sample <= 24; sample++) {
            final int n = sample;
            test("dedup/conflict/" + sample, () -> {
                EventStore store = new EventStore();
                Event first = new Event("a", "same", n, 10);
                equal(true, store.accept(first));
                equal(false, store.accept(first));
                equal(true, store.accept(new Event("b", "same", n, 10)));
                rejects(() -> store.accept(new Event("a", "same", n + 1, 10)));
                rejects(() -> store.accept(new Event("a", "same", n, 11)));
                equal(first, store.all().get(0));
                equal(2, store.all().size());
            });
            test("window/boundaries/" + sample, () -> {
                long width = n * 10L;
                List<Event> events = List.of(new Event("a", "left", -width, 2),
                        new Event("a", "negative", -1, 3), new Event("a", "zero", 0, 5),
                        new Event("a", "excluded", width, 100), new Event("b", "other", 0, 100));
                equal(Map.of(-width, 5L, 0L, 5L), new WindowAggregator().totals(events, "a", -width, width, width));
            });
            for (int p : new int[]{1, 25, 50, 75, 90, 99, 100}) {
                test("percentile/" + sample + "/" + p, () -> {
                    List<Long> values = new ArrayList<>();
                    for (int i = n; i >= 1; i--) values.add(i * 7L);
                    List<Long> original = List.copyOf(values);
                    long expected = ((p * n + 99L) / 100) * 7;
                    equal(expected, new Percentiles().nearestRank(values, p));
                    equal(original, values);
                    equal(expected, new Percentiles().nearestRank(original, p));
                });
            }
        }
        for (int pageSize = 1; pageSize <= 11; pageSize++) {
            final int size = pageSize;
            test("cursor/exhaustive/" + pageSize, () -> {
                List<Event> input = new ArrayList<>();
                for (int index = 0; index < 60; index++) {
                    input.add(new Event("tenant-" + index % 3, "id-" + index, index % 4, index));
                }
                Collections.shuffle(input, new Random(size));
                List<Event> expected = new ArrayList<>(input);
                expected.sort(Comparator.comparingLong(Event::timestamp).thenComparing(Event::tenant).thenComparing(Event::id));
                List<Event> seen = new ArrayList<>();
                Event cursor = null;
                for (int call = 0; call < 62; call++) {
                    List<Event> page = new CursorPager().page(input, cursor, size);
                    if (page.isEmpty()) break;
                    seen.addAll(page);
                    cursor = page.getLast();
                }
                equal(expected, seen);
            });
        }
        test("empty-and-invalid", () -> {
            equal(List.of(), new CursorPager().page(List.of(), null, 1));
            rejects(() -> new CursorPager().page(List.of(), null, 0));
            rejects(() -> new Percentiles().nearestRank(List.of(), 50));
            rejects(() -> new Percentiles().nearestRank(List.of(1L), 0));
            rejects(() -> new Percentiles().nearestRank(List.of(1L), 101));
        });
        finish("ORACLE");
    }
}
