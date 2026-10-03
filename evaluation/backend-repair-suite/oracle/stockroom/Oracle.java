package stockroom;

import java.util.Map;
import static testing.Checks.*;

public final class Oracle {
    public static void main(String[] args) {
        for (int sample = 1; sample <= 24; sample++) {
            final int quantity = sample;
            test("reserve/exact/" + sample, () -> {
                Inventory stock = new Inventory(Map.of("A", quantity));
                new ReservationService(stock).reserve("r", "A", quantity, 20, 10);
                equal(0, stock.available("A"));
            });
            test("reserve/rejected-unchanged/" + sample, () -> {
                Inventory stock = new Inventory(Map.of("A", quantity));
                ReservationService service = new ReservationService(stock);
                rejects(() -> service.reserve("zero", "A", 0, 20, 10));
                rejects(() -> service.reserve("negative", "A", -quantity, 20, 10));
                rejects(() -> service.reserve("too-many", "A", quantity + 1, 20, 10));
                equal(quantity, stock.available("A"));
            });
            test("replay/full-identity/" + sample, () -> {
                Inventory stock = new Inventory(Map.of("A", 100, "B", 100));
                ReservationService service = new ReservationService(stock);
                Reservation first = service.reserve("r", "A", quantity, 50, 10);
                equal(first, service.reserve("r", "A", quantity, 50, 999));
                rejects(() -> service.reserve("r", "B", quantity, 50, 10));
                rejects(() -> service.reserve("r", "A", quantity, 51, 10));
                rejects(() -> service.reserve("r", "A", quantity + 1, 50, 10));
                equal(Map.of("A", 100 - quantity, "B", 100), stock.snapshot());
            });
            test("expiry/exactly-once/" + sample, () -> {
                Inventory stock = new Inventory(Map.of("A", 100));
                ReservationService service = new ReservationService(stock);
                Reservation reservation = service.reserve("r", "A", quantity, 50, 10);
                equal(0, service.expire(59));
                equal(1, service.expire(60));
                equal(0, service.expire(61));
                equal(false, reservation.active());
                equal(100, stock.available("A"));
            });
            test("transfer/failed-prepare/" + sample, () -> {
                Inventory source = new Inventory(Map.of("A", 100));
                Inventory full = new Inventory(Map.of("A", Inventory.MAX));
                Inventory missing = new Inventory(Map.of("B", 100));
                TransferService transfer = new TransferService();
                rejects(() -> transfer.transfer(source, full, "A", quantity));
                equal(100, source.available("A"));
                rejects(() -> transfer.transfer(source, missing, "A", quantity));
                equal(100, source.available("A"));
                rejects(() -> transfer.transfer(source, source, "A", quantity));
                equal(100, source.available("A"));
            });
            test("transfer/conservation/" + sample, () -> {
                Inventory source = new Inventory(Map.of("A", 100));
                Inventory target = new Inventory(Map.of("A", 40));
                new TransferService().transfer(source, target, "A", quantity);
                equal(100 - quantity, source.available("A"));
                equal(40 + quantity, target.available("A"));
            });
            test("batch/all-or-nothing/" + sample, () -> {
                for (String invalid : new String[]{"B,-1", "A,1", "unknown,2", "B," + Inventory.MAX, "bad-row"}) {
                    Inventory stock = new Inventory(Map.of("A", 100, "B", 50));
                    rejects(() -> new RestockService().importCsv(stock, "A," + quantity + "\n" + invalid));
                    equal(Map.of("A", 100, "B", 50), stock.snapshot());
                }
            });
            test("workflow/restock-reserve-release/" + sample, () -> {
                Inventory stock = new Inventory(Map.of("A", 100, "B", 50));
                new RestockService().importCsv(stock, "# shipment\n A , " + quantity + "\nB,2\n");
                ReservationService service = new ReservationService(stock);
                service.reserve("r", "A", quantity, 5, 0);
                equal(100, stock.available("A"));
                equal(1, service.expire(5));
                equal(Map.of("A", 100 + quantity, "B", 52), stock.snapshot());
            });
        }
        finish("ORACLE");
    }
}
