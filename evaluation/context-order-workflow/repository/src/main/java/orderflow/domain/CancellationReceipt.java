package orderflow.domain;

public record CancellationReceipt(String orderId, boolean changed, OrderStatus status, long version) {}
