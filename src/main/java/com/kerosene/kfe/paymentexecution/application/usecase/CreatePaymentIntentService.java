package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.CreatePaymentIntentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.CreatePaymentIntentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentIntentStore;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentIntent;

/** Normalizes the existing intent representation without carrying persistence or transport types. */
public final class CreatePaymentIntentService implements CreatePaymentIntentUseCase {

    /** Persists the normalized intent and returns its generated execution identity. */
    private final PaymentIntentStore store;

    /** Creates intent orchestration with the application-owned intent store. */
    /** @param store payment intent persistence port */
    public CreatePaymentIntentService(PaymentIntentStore store) {
        this.store = store;
    }

    /** Creates the initial payment intent after trimming optional request references and memo. */
    /** @param command validated creation-only payment inputs @return generated execution identity */
    @Override
    public PaymentExecutionId create(CreatePaymentIntentCommand command) {
        String publicId = clean(command.paymentRequestPublicId());
        var intent = new PaymentIntent(
                command.userId(), command.idempotencyKey(), command.rail(), command.direction(),
                command.sourceWalletId(), command.destinationWalletId(), command.amountSats(),
                publicId != null ? publicId : clean(command.externalReference()), clean(command.memo()));
        return store.create(intent);
    }

    /** Trims optional text and converts null or blank values to null. */
    /** @param value raw optional input @return trimmed content or null when absent */
    private static String clean(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
