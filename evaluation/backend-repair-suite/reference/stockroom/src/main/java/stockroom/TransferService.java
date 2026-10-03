package stockroom;

public final class TransferService {
    public void transfer(Inventory source, Inventory target, String sku, int quantity) {
        if (source == target || quantity <= 0) throw new IllegalArgumentException("invalid transfer");
        int from = source.available(sku);
        if (quantity > from) throw new IllegalArgumentException("insufficient stock");
        int to = Math.addExact(target.available(sku), quantity);
        if (to > Inventory.MAX) throw new IllegalArgumentException("target full");
        source.set(sku, from - quantity);
        target.set(sku, to);
    }
}
