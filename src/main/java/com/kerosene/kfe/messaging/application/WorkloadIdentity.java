package com.kerosene.kfe.messaging.application;

import java.util.Objects;

/** Authenticated workload identity supplied by the transport, never by message payload.
 * @param spiffeId canonical SPIFFE URI authenticated by the transport adapter
 */
public record WorkloadIdentity(String spiffeId) {
    /** Rejects absent, non-SPIFFE, or overlong identities before they reach authorization policy. */
    public WorkloadIdentity {
        Objects.requireNonNull(spiffeId, "spiffeId is required");
        if (!spiffeId.startsWith("spiffe://") || spiffeId.length() > 255) {
            throw new IllegalArgumentException("workload identity must be a valid SPIFFE id");
        }
    }
}
