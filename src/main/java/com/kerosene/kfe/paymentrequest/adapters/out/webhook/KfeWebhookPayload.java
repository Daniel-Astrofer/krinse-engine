package com.kerosene.kfe.paymentrequest.adapters.out.webhook;

import java.time.Instant;
import java.util.UUID;

/**
 * Signed webhook payload delivered to external endpoints.
 */
public record KfeWebhookPayload(
        UUID eventId,
        KfeWebhookEvent eventType,
        Instant timestamp,
        String paymentRequestPublicId,
        Long amountSats,
        String status,
        String signature) {
}
