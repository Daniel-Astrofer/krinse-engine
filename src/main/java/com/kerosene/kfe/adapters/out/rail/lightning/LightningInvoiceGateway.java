package com.kerosene.kfe.adapters.out.rail.lightning;

import com.kerosene.kfe.adapters.out.rail.custody.CustodyGateway;

import java.util.function.Consumer;

/**
 * Provider boundary for creating, querying, cancelling, and optionally streaming Lightning invoices.
 *
 * <p>Implementations may use custody APIs or a Lightning node; {@link #isLive()} allows application
 * services to distinguish a configured provider from a disabled fallback.
 */
public interface LightningInvoiceGateway {

    /** @return true when invoice operations are backed by an active provider */
    boolean isLive();

    /** @return stable provider name suitable for logs and persisted operation metadata */
    String providerName();

    /**
     * Creates a Lightning invoice for the requested wallet and amount.
     *
     * @param command invoice amount, expiry, memo, and wallet context
     * @return generated request, payment identity, provider reference, and expiry
     */
    CustodyGateway.GeneratedLightningInvoice createLightningInvoice(CustodyGateway.LightningInvoiceCommand command);

    /**
     * Retrieves the current state and received amount for an invoice.
     *
     * @param command invoice lookup identifiers and wallet context
     * @return provider status and available settlement details
     */
    CustodyGateway.IncomingLightningInvoiceStatus getLightningInvoiceStatus(CustodyGateway.LightningInvoiceStatusCommand command);

    /**
     * Requests cancellation of an invoice that has not settled.
     *
     * @param command provider reference, payment hash/request, and wallet context
     * @return true when the provider confirms cancellation
     */
    boolean cancelLightningInvoice(CustodyGateway.LightningInvoiceCancellationCommand command);

    /**
     * Subscribe to real-time invoice updates from the Lightning node.
     * Implementations call {@code handler} for each invoice update event.
     * Returns a subscription handle that can be used to unsubscribe.
     * Default no-op for adapters that don't support streaming.
     *
     * @param handler callback for each invoice update
     * @return subscription handle (null if not supported)
     */
    default InvoiceSubscription subscribeInvoices(Consumer<CustodyGateway.IncomingLightningInvoiceStatus> handler) {
        return null;
    }

    /** Handle returned by {@link #subscribeInvoices} for ending the event stream. */
    interface InvoiceSubscription {
        /** Stops delivery of future invoice updates for this subscription. */
        void unsubscribe();
    }
}
