package orderflow;

import orderflow.audit.EventLog;
import orderflow.batch.BatchImporter;
import orderflow.catalog.Catalog;
import orderflow.inventory.Inventory;
import orderflow.order.CancellationService;
import orderflow.order.OrderRepository;
import orderflow.order.OrderService;
import orderflow.pricing.PricingPolicy;
import orderflow.pricing.PricingService;
import orderflow.pricing.ShippingPolicy;
import orderflow.query.OrderQuery;
import orderflow.request.RequestCoordinator;
import orderflow.request.RequestJournal;
import java.util.function.LongSupplier;

/** Explicit wiring: each application has its own state, no singletons or hidden global fixtures. */
public final class Application {
    public final Catalog catalog;
    public final Inventory inventory;
    public final EventLog events;
    public final OrderRepository orders;
    public final OrderService service;
    public final CancellationService cancellation;
    public final RequestJournal journal;
    public final RequestCoordinator requests;
    public final BatchImporter importer;
    public final OrderQuery query;
    public final PricingService pricing;

    public Application(Catalog catalog, LongSupplier clock) {
        this.catalog = catalog;
        inventory = new Inventory(catalog);
        events = new EventLog(clock);
        orders = new OrderRepository();
        pricing = new PricingService(catalog, new PricingPolicy(), new ShippingPolicy());
        service = new OrderService(orders, inventory, pricing, events);
        cancellation = new CancellationService(orders, inventory, events);
        journal = new RequestJournal();
        requests = new RequestCoordinator(journal, service);
        importer = new BatchImporter(requests);
        query = new OrderQuery(orders, events);
    }
}
