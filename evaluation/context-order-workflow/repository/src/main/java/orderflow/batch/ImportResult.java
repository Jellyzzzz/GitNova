package orderflow.batch;

import java.util.List;

public record ImportResult(String batchId, List<RowResult> rows) {
    public enum Status { ACCEPTED, REPLAYED, REJECTED }
    public record RowResult(int line, Status status, String orderId, String error) {}

    public ImportResult { rows = List.copyOf(rows); }
    public long accepted() { return rows.stream().filter(row -> row.status() == Status.ACCEPTED).count(); }
    public long rejected() { return rows.stream().filter(row -> row.status() == Status.REJECTED).count(); }
    public long replayed() { return rows.stream().filter(row -> row.status() == Status.REPLAYED).count(); }
}
