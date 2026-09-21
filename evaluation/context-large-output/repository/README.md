# Order pricing regression

A dependency-free Java 21 order quotation service. Amounts are integer cents.

Contract:
- Accept subtotal in [0, 1,000,000] and percentage in [0, 100].
- Discount is subtotal × percentage / 100, rounded to the nearest cent with HALF_UP.
- Free shipping applies when the **discounted merchandise subtotal** is at least 5,000 cents.
- Otherwise shipping costs 600 cents, including an order whose discounted subtotal is zero.
- Total = subtotal - rounded discount + shipping.
- No network, Maven downloads, database or application server is required.

Run the actual regression suite:

```sh
sh run-tests.sh
```

The CSV is a set of fixed expected order totals, not generated from production code.
The batch runner emits one diagnostic per executed order and exits nonzero on failure.
Diagnostics use a column header and compact numeric rows; orderNumber 65 means CSV order ORD-065.
Rows prefixed FAIL violate the expected total. Intermediate expected/actual amounts help locate the cause.
This compact format keeps the complete suite below the command Gateway's 8 KiB capture limit.
Build output is placed in /tmp, not in this repository.

For the repair task, change only DiscountPolicy.java and ShippingPolicy.java.
Preserve the public API, fixture data, test runner and this contract.
