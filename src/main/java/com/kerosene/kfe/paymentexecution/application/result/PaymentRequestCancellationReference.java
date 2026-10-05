package com.kerosene.kfe.paymentexecution.application.result;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationStatus;
import java.util.UUID;

/**
 * Only the user-scoped request metadata needed to present cancellation eligibility.
 * @param id payment request identifier
 * @param userId account that owns the request
 * @param publicId client-visible request identifier
 * @param status projected request lifecycle status
 */
public record PaymentRequestCancellationReference(
        UUID id, long userId, String publicId, PaymentRequestCancellationStatus status) {}
