package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentInvoiceCommand;

/**
 * Asks the invoice provider to cancel and reports whether it confirmed cancellation.
 * A provider-side cancellation cannot be undone by a local transaction rollback.
 */
public interface PaymentInvoiceCancellationPort {

    /** Calls the provider-side cancellation API; the external effect cannot be undone by local rollback. */
    /** @param command owner and provider invoice identifiers @return true only when provider confirms cancellation */
    boolean cancel(CancelPaymentInvoiceCommand command);
}
