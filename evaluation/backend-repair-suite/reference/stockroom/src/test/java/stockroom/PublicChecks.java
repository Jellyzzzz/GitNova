package stockroom;

import java.util.Map;
import static testing.Checks.*;

public final class PublicChecks {
    public static void main(String[] args) {
        test("reserve/exact-stock", () -> {
            Inventory inventory = new Inventory(Map.of("A", 4));
            new ReservationService(inventory).reserve("r", "A", 4, 10, 100);
            equal(0, inventory.available("A"));
        });
        test("reserve/insufficient", () -> {
            Inventory inventory = new Inventory(Map.of("A", 4));
            rejects(() -> new ReservationService(inventory).reserve("r", "A", 5, 10, 100));
            equal(4, inventory.available("A"));
        });
        test("replay/conflict", () -> {
            Inventory inventory = new Inventory(Map.of("A", 10));
            ReservationService service = new ReservationService(inventory);
            Reservation first = service.reserve("r", "A", 2, 10, 100);
            equal(first, service.reserve("r", "A", 2, 10, 999));
            rejects(() -> service.reserve("r", "A", 3, 10, 100));
            equal(8, inventory.available("A"));
        });
        test("expiry/deadline", () -> {
            Inventory inventory = new Inventory(Map.of("A", 10));
            ReservationService service = new ReservationService(inventory);
            service.reserve("r", "A", 2, 10, 100);
            equal(0, service.expire(109));
            equal(1, service.expire(110));
            equal(10, inventory.available("A"));
        });
        test("transfer/reject-overflow-without-debit", () -> {
            Inventory a = new Inventory(Map.of("A", 10));
            Inventory b = new Inventory(Map.of("A", Inventory.MAX));
            rejects(() -> new TransferService().transfer(a, b, "A", 2));
            equal(10, a.available("A"));
        });
        test("transfer/conservation", () -> {
            Inventory a = new Inventory(Map.of("A", 10));
            Inventory b = new Inventory(Map.of("A", 0));
            new TransferService().transfer(a, b, "A", 4);
            equal(6, a.available("A"));
            equal(4, b.available("A"));
        });
        test("batch/no-partial-restock", () -> {
            Inventory inventory = new Inventory(Map.of("A", 10, "B", 20));
            rejects(() -> new RestockService().importCsv(inventory, "A,2\nB,-1"));
            equal(Map.of("A", 10, "B", 20), inventory.snapshot());
        });
        test("batch/valid", () -> {
            Inventory inventory = new Inventory(Map.of("A", 10, "B", 20));
            new RestockService().importCsv(inventory, "# delivery\nA,2\n\nB,3");
            equal(Map.of("A", 12, "B", 23), inventory.snapshot());
        });
        finish("PUBLIC");
    }
}
