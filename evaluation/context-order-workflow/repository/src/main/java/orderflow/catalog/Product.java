package orderflow.catalog;

import orderflow.domain.Money;
import orderflow.domain.OrderLine;

public record Product(String sku, long unitCents, int initialStock) {
    public Product {
        new OrderLine(sku, 1);
        Money.nonNegative(unitCents);
        if (initialStock < 0) throw new IllegalArgumentException("negative stock");
    }
}
