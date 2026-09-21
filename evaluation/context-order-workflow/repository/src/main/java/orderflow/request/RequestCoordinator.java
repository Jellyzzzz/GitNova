package orderflow.request;

import orderflow.domain.OrderReceipt;
import orderflow.domain.PlaceOrder;
import orderflow.order.OrderService;

public final class RequestCoordinator {
    private final RequestJournal journal;
    private final OrderService orders;

    public RequestCoordinator(RequestJournal journal, OrderService orders) {
        this.journal = journal;
        this.orders = orders;
    }

    public OrderReceipt submit(PlaceOrder request) {
        String fingerprint = RequestFingerprint.of(request);
        var previous = journal.find(request.actorId(), request.requestId());
        if (previous != null) {
            if (!previous.fingerprint().equals(fingerprint)) {
                throw new IllegalStateException("request intent conflict");
            }
            return previous.receipt().asReplay();
        }
        var receipt = orders.place(request);
        journal.save(request.actorId(), request.requestId(), fingerprint, receipt);
        return receipt;
    }
}
