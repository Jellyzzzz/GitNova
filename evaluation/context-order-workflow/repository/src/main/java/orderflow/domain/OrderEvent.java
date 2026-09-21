package orderflow.domain;

public record OrderEvent(long sequence, String orderId, String type, long atMillis) {}
