package orderflow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;

public final class AllTests {
    public static void main(String[] args) {
        Map<String, IntSupplier> suites = new LinkedHashMap<>();
        suites.put("contract", ContractChecks::run);
        suites.put("inventory", InventoryChecks::run);
        suites.put("pricing", PricingChecks::run);
        suites.put("cancellation", CancellationChecks::run);
        suites.put("idempotency", IdempotencyChecks::run);
        suites.put("batch", BatchChecks::run);
        suites.put("workflow", WorkflowChecks::run);
        String selected = args.length == 0 ? "all" : args[0];
        if (!selected.equals("all") && !suites.containsKey(selected)) {
            throw new IllegalArgumentException("unknown suite: " + selected);
        }
        int failed = 0;
        for (var suite : suites.entrySet()) {
            if (selected.equals("all") || selected.equals(suite.getKey())) failed += suite.getValue().getAsInt();
        }
        System.out.println("ALL SUMMARY selection=" + selected + " failed=" + failed);
        if (failed != 0) System.exit(1);
    }
}
