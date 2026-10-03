package stockroom;

public final class Reservation {
    public final String requestId;
    public final String sku;
    public final int quantity;
    public final long ttl;
    public final long expiresAt;
    private boolean active = true;

    public Reservation(String requestId, String sku, int quantity, long ttl, long expiresAt) {
        this.requestId = requestId;
        this.sku = sku;
        this.quantity = quantity;
        this.ttl = ttl;
        this.expiresAt = expiresAt;
    }

    public boolean active() { return active; }
    public void deactivate() { active = false; }
}
