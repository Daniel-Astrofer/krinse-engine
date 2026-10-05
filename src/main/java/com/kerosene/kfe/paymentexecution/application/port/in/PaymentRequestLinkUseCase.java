package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentRequestLinkCommand;
import com.kerosene.kfe.paymentexecution.application.command.CompletePaymentRequestLinkCommand;
import com.kerosene.kfe.paymentexecution.application.result.PreparedPaymentRequestLink;
import java.util.Optional;

/** Accepts a request during payment preparation and links it after successful settlement. */
public interface PaymentRequestLinkUseCase {
    /** @param command request, recipient wallet, rail, and amount @return prepared request snapshot, or empty when no request was supplied */
    Optional<PreparedPaymentRequestLink> prepare(PreparePaymentRequestLinkCommand command);
    /** @param command accepted request snapshot and settled execution identity */
    void complete(CompletePaymentRequestLinkCommand command);
}
