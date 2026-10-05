package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Fenced ownership of one durable command; possession must be revalidated before dispatch.
 * @param outboxId durable command identifier
 * @param claimToken lease token proving current worker ownership
 */
public record ExecutionClaim(UUID outboxId, UUID claimToken) {
    /** Requires both the durable command identity and its worker lease token. */
    public ExecutionClaim {
        Objects.requireNonNull(outboxId, "outbox id is required");
        Objects.requireNonNull(claimToken, "claim token is required");
    }
    /** Returns the command identity while redacting the lease token. */
    @Override public String toString() { return "ExecutionClaim[outboxId=" + outboxId + ", claimToken=REDACTED]"; }
}
