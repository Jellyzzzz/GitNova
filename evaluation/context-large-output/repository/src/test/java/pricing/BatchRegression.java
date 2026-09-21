package pricing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Each diagnostic corresponds to a real independently specified order fixture. */
public final class BatchRegression {
    public static void main(String[] args) throws Exception {
        PricingService pricing = new PricingService();
        int passed = 0;
        int failed = 0;
        System.out.println("columns=[orderNumber,subtotal,percent,expectedDiscount,discount,expectedNet,net,expectedShipping,shipping,expectedTotal,total]");
        List<String> rows = Files.readAllLines(Path.of(args[0]));
        for (String row : rows.subList(1, rows.size())) {
            String[] columns = row.split(",");
            String id = columns[0];
            long subtotal = Long.parseLong(columns[1]);
            int percent = Integer.parseInt(columns[2]);
            long expected = Long.parseLong(columns[3]);
            PricingService.Quote actual = pricing.quote(subtotal, percent);
            boolean success = actual.totalCents() == expected;
            long expectedDiscount = (subtotal * percent + 50) / 100;
            long expectedNet = subtotal - expectedDiscount;
            System.out.printf("%s[%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d]%n",
                    success ? "" : "FAIL ", Integer.parseInt(id.substring(4)), subtotal, percent,
                    expectedDiscount, actual.discountCents(), expectedNet, subtotal - actual.discountCents(),
                    expectedNet >= 5000 ? 0 : 600, actual.shippingCents(), expected, actual.totalCents());
            if (success) {
                passed++;
            } else {
                failed++;
            }
        }
        System.out.printf("BATCH SUMMARY cases=%d passed=%d failed=%d%n", passed + failed, passed, failed);
        if (failed > 0) System.exit(1);
    }
}
