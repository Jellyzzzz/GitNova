package orderflow.batch;

import orderflow.request.RequestCoordinator;
import java.util.ArrayList;
import java.util.List;

public final class BatchImporter {
    private final RequestCoordinator requests;

    public BatchImporter(RequestCoordinator requests) { this.requests = requests; }

    public ImportResult importRows(String batchId, List<String> rawRows) {
        if (batchId == null || batchId.isBlank() || rawRows == null) {
            throw new IllegalArgumentException("invalid batch");
        }
        var rows = new ArrayList<ImportResult.RowResult>();
        for (int i = 0; i < rawRows.size(); i++) {
            try {
                var receipt = requests.submit(ImportRow.parse(rawRows.get(i)).request());
                rows.add(new ImportResult.RowResult(i + 1, receipt.replayed()
                        ? ImportResult.Status.REPLAYED : ImportResult.Status.ACCEPTED, receipt.orderId(), null));
            } catch (IllegalArgumentException | IllegalStateException rejected) {
                rows.add(new ImportResult.RowResult(i + 1, ImportResult.Status.REJECTED, null, rejected.getMessage()));
            }
        }
        return new ImportResult(batchId, rows);
    }
}
