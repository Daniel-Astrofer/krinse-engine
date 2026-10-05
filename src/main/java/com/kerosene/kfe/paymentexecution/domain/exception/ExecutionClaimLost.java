package com.kerosene.kfe.paymentexecution.domain.exception;

import java.util.UUID;

public final class ExecutionClaimLost extends RuntimeException {
    public ExecutionClaimLost(UUID outboxId) {
        super("Execution claim is no longer owned for outbox " + outboxId + ".");
    }
}
