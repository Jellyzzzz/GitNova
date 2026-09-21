package orderflow.domain;

public record OrderLine(String sku, int quantity) {
    public OrderLine {
        if (sku == null || !sku.matches("[A-Z][A-Z0-9-]{0,31}")) {
            throw new IllegalArgumentException("invalid SKU");
        }
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
    }
}
