package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.common.exception.FinancialSelfPaymentException;
import com.kerosene.kfe.paymentexecution.application.command.ResolvePaymentWalletsCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentWalletsUseCase;
import com.kerosene.kfe.paymentexecution.application.result.PaymentWalletSelection;
import com.kerosene.kfe.paymentexecution.application.usecase.PaymentWalletsService;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentSelfTransferRejected;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Preflight stays outside the financial transaction; wallet selection joins it for source locking. */
@Component
public class PaymentWalletsAdapter implements PaymentWalletsUseCase {
    /** Wallet policy for owner-scoped destination lookup and locked source selection. */
    private final PaymentWalletsService service;

    /** Wires the application service whose transaction semantics this adapter enforces. */
    public PaymentWalletsAdapter(PaymentWalletsService service) { this.service = service; }

    /** Resolves the destination reference using the wallet policy. */
    @Override
    public UUID resolveDestinationReference(ResolvePaymentWalletsCommand command) {
        return service.resolveDestinationReference(command);
    }

    /** Rejects a self-payment and translates the domain rejection to the legacy financial exception. */
    @Override
    public void requireNotSelfPayment(ResolvePaymentWalletsCommand command) {
        try { service.requireNotSelfPayment(command); }
        catch (PaymentSelfTransferRejected rejected) { throw new FinancialSelfPaymentException(); }
    }

    /** Resolves wallets while joining the mandatory transaction that locks the source. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentWalletSelection resolve(ResolvePaymentWalletsCommand command) { return service.resolve(command); }
}
