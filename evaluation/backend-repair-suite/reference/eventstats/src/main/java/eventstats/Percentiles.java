package eventstats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Percentiles {
    public long nearestRank(List<Long> values, int percentile) {
        if (values.isEmpty() || percentile < 1 || percentile > 100) throw new IllegalArgumentException("invalid percentile");
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) (((long) percentile * sorted.size() + 99) / 100) - 1;
        return sorted.get(index);
    }
}
