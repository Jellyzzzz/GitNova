package orderflow;

import orderflow.catalog.Catalog;
import orderflow.domain.OrderLine;
import orderflow.domain.PlaceOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class TestSupport {
    private final String suite;
    private int checks;
    private int failed;

    public TestSupport(String suite) { this.suite = suite; }

    public void check(String id, Object expected, Supplier<?> observation) {
        Object actual;
        try {
            actual = observation.get();
        } catch (RuntimeException exception) {
            actual = exception.getClass().getSimpleName() + ":" + exception.getMessage();
        }
        checks++;
        boolean pass = Objects.equals(expected, actual);
        if (!pass) failed++;
        System.out.println("CASE|" + suite + "|" + id + "|expected=" + expected
                + "|actual=" + actual + "|" + (pass ? "PASS" : "FAIL"));
    }

    public int finish() {
        System.out.println("SUITE " + suite + " checks=" + checks + " failed=" + failed);
        return failed;
    }

    public static Application fixture() {
        try {
            return new Application(Catalog.load(Path.of("fixtures/catalog.csv")), () -> 123456L);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("fixture cannot load", failure);
        }
    }

    public static PlaceOrder request(String key, String actor, int percent, OrderLine... lines) {
        return new PlaceOrder(key, actor, "east", List.of(lines), percent);
    }

    public static boolean rejected(Runnable operation) {
        try { operation.run(); return false; }
        catch (IllegalArgumentException | IllegalStateException | ArithmeticException expected) { return true; }
    }
}
