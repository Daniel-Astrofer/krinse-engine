package com.kerosene.kfe.adapters.out.rail.lightning;

import com.kerosene.kfe.adapters.out.rail.custody.CustodyGateway;

/** Explicitly disabled Lightning rail; never creates invoices or signs payments. */
public final class DisabledLightningGateway implements LightningInvoiceGateway, LightningPaymentGateway {
    @Override public boolean isLive() { return false; }
    @Override public String providerName() { return "DISABLED"; }
    @Override public CustodyGateway.GeneratedLightningInvoice createLightningInvoice(CustodyGateway.LightningInvoiceCommand command) { throw disabled(); }
    @Override public CustodyGateway.IncomingLightningInvoiceStatus getLightningInvoiceStatus(CustodyGateway.LightningInvoiceStatusCommand command) { throw disabled(); }
    @Override public boolean cancelLightningInvoice(CustodyGateway.LightningInvoiceCancellationCommand command) { throw disabled(); }
    @Override public CustodyGateway.PaymentResult payLightning(CustodyGateway.LightningPaymentCommand command) { throw disabled(); }
    private IllegalStateException disabled() { return new IllegalStateException("Lightning is disabled until threshold-distributed custody is available."); }
}
