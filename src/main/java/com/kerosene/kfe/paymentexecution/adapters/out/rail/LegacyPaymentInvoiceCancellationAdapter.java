package com.kerosene.kfe.paymentexecution.adapters.out.rail;

import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentInvoiceCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentInvoiceCancellationPort;
import com.kerosene.kfe.adapters.out.rail.custody.CustodyGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningInvoiceGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Preserves provider routing while the caller holds its local cancellation locks. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class LegacyPaymentInvoiceCancellationAdapter implements PaymentInvoiceCancellationPort {

    private final LightningInvoiceGateway invoiceGateway;

    public LegacyPaymentInvoiceCancellationAdapter(
            @Qualifier("kfeExternalLightningInvoiceGateway") LightningInvoiceGateway invoiceGateway) {
        this.invoiceGateway = invoiceGateway;
    }

    @Override
    public boolean cancel(CancelPaymentInvoiceCommand command) {
        Objects.requireNonNull(command, "invoice cancellation command is required");
        return invoiceGateway.cancelLightningInvoice(new CustodyGateway.LightningInvoiceCancellationCommand(
                command.userId(),
                null,
                null,
                command.paymentHash(),
                command.providerReference(),
                command.paymentRequest()));
    }
}
