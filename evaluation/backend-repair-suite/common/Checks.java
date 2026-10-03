package testing;

import java.io.IOException;
import java.util.Objects;

public final class Checks {
    @FunctionalInterface public interface Action { void run() throws Exception; }
    private static int checks;
    private static int failed;

    public static void test(String label, Action action) {
        checks++;
        try { action.run(); }
        catch (Throwable failure) {
            failed++;
            System.out.println("FAIL " + label + ": " + failure);
        }
    }

    public static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected=" + expected + ", actual=" + actual);
    }

    public static void rejects(Action action) throws Exception {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("expected IllegalArgumentException");
    }

    public static void ioFails(Action action) throws Exception {
        try { action.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("expected IOException");
    }

    public static void finish(String kind) {
        System.out.println(kind + " checks=" + checks + " failed=" + failed);
        if (failed != 0) System.exit(1);
    }
}
