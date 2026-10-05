package com.kerosene.kfe.paymentexecution.application.result;

import java.util.UUID;

/**
 * Preliminary UI indication, never a substitute for action authorization or the batch fence.
 * @param cancellable whether the current projection appears eligible for cancellation
 * @param cancelTarget request or transaction aggregate that a cancellation action targets
 * @param paymentRequestId linked request identity when request cancellation is selected
 * @param paymentRequestPublicId client-visible linked request identity, if available
 * @param paymentRequestStatus current projected request lifecycle status
 */
public record PaymentCancellationHints(
        boolean cancellable, String cancelTarget, UUID paymentRequestId,
        String paymentRequestPublicId, String paymentRequestStatus) {
    /** Target discriminator returned when the request aggregate should be cancelled. */
    public static final String PAYMENT_REQUEST = "PAYMENT_REQUEST";
    /** Target discriminator returned when only the execution should be cancelled. */
    public static final String TRANSACTION = "TRANSACTION";

    /** Creates a deny-by-default empty hint without exposing action targets. */
    /** @return non-cancellable hints with no linked request */
    public static PaymentCancellationHints none() {
        return new PaymentCancellationHints(false, null, null, null, null);
    }
}
