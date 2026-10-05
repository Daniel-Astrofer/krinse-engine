package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.ResolvePaymentWalletsCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentWalletSelection;
import java.util.UUID;

/** Resolves and validates owned source/destination wallets for a payment direction. */
public interface PaymentWalletsUseCase {
    /** @param command payment route and destination reference @return resolved destination wallet ID, or null when absent */
    UUID resolveDestinationReference(ResolvePaymentWalletsCommand command);
    /** @param command source/destination selection @throws PaymentSelfTransferRejected when sender destination resolves to the same owned wallet */
    void requireNotSelfPayment(ResolvePaymentWalletsCommand command);
    /** @param command authenticated route selection @return owned and spendable wallet snapshots */
    PaymentWalletSelection resolve(ResolvePaymentWalletsCommand command);
}
