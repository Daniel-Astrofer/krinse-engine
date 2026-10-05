package com.kerosene.kfe.messaging.inbox;

import java.time.Instant;

/** Pure redelivery and quarantine policy for at-least-once consumers. */
public final class InboxDeliveryPolicy {

    public InboxDeliveryDecision decide(
            String status,
            Instant leaseUntil,
            Instant now,
            int attempts,
            int maxAttempts,
            boolean validEnvelope) {
        if (!validEnvelope) {
            return InboxDeliveryDecision.QUARANTINE;
        }
        if ("PROCESSED".equals(status)) {
            return InboxDeliveryDecision.DUPLICATE;
        }
        if ("QUARANTINED".equals(status)) {
            return InboxDeliveryDecision.QUARANTINE;
        }
        if (attempts < 0 || maxAttempts < 1) {
            throw new IllegalArgumentException("attempt counters are invalid");
        }
        if (attempts >= maxAttempts) {
            return InboxDeliveryDecision.QUARANTINE;
        }
        if ("PROCESSING".equals(status) && leaseUntil != null && leaseUntil.isAfter(now)) {
            return InboxDeliveryDecision.DEFER;
        }
        return InboxDeliveryDecision.PROCESS;
    }
}
