package stockroom;

import java.util.ArrayList;
import java.util.List;

public final class StockCsv {
    public record Row(String sku, int quantity) {}

    public List<Row> parse(String csv) {
        List<Row> rows = new ArrayList<>();
        for (String line : csv.split("\\R")) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            String[] fields = line.split(",", -1);
            if (fields.length != 2 || fields[0].isBlank()) throw new IllegalArgumentException("invalid row");
            rows.add(new Row(fields[0].trim(), Integer.parseInt(fields[1].trim())));
        }
        return rows;
    }
}
