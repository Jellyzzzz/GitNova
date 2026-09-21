package orderflow.domain;

import orderflow.pricing.Quote;

/** Original submission result, not the later live status of the order. */
public record OrderReceipt(String orderId, Quote quote, boolean replayed) {
    public OrderReceipt asReplay() { return new OrderReceipt(orderId, quote, true); }
}
