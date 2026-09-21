package orderflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class BatchChecks {
    public static int run() {
        var t = new TestSupport("batch");
        t.check("bad-middle-keeps-tail", "3/2/1/998", () -> {
            var f = TestSupport.fixture();
            var r = f.importer.importRows("batch", List.of("a,alice,PEN,1,0,east", "bad",
                    "b,alice,PEN,1,0,east"));
            return r.rows().size() + "/" + r.accepted() + "/" + r.rejected() + "/" + f.inventory.available("PEN");
        });
        t.check("bad-first-valid-last", "2/1/2", () -> {
            var f = TestSupport.fixture();
            var r = f.importer.importRows("batch", List.of("", "x,alice,PEN,1,0,east"));
            return r.rows().size() + "/" + r.accepted() + "/" + r.rows().get(r.rows().size() - 1).line();
        });
        t.check("replay-no-new-stock", "1/1/999", () -> {
            var f = TestSupport.fixture();
            var r = f.importer.importRows("batch", List.of("a,alice,PEN,1,0,east", "a,alice,PEN,1,0,east"));
            return r.accepted() + "/" + r.replayed() + "/" + f.inventory.available("PEN");
        });
        t.check("out-of-stock-continues", 1L, () -> TestSupport.fixture().importer.importRows("batch",
                List.of("x,alice,BAG,101,0,east", "y,alice,PEN,1,0,east")).accepted());
        t.check("empty-batch", 0, () -> TestSupport.fixture().importer.importRows("batch", List.of()).rows().size());
        t.check("csv-combination", "10/5/4/1/5", () -> {
            var f = TestSupport.fixture();
            try {
                var lines = Files.readAllLines(Path.of("fixtures/import.csv"));
                var r = f.importer.importRows("sample", lines.subList(1, lines.size()));
                return r.rows().size() + "/" + r.accepted() + "/" + r.rejected() + "/" + r.replayed() + "/" + f.orders.size();
            } catch (java.io.IOException error) {
                throw new IllegalStateException(error);
            }
        });
        return t.finish();
    }
}
