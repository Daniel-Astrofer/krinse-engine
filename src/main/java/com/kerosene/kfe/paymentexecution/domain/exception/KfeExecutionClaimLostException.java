package com.kerosene.kfe.paymentexecution.domain.exception;

import java.util.UUID;

public final class KfeExecutionClaimLostException extends RuntimeException {

    public KfeExecutionClaimLostException(UUID outboxId) {
        super("KFE execution claim is no longer owned for outbox " + outboxId + ".");
    }
}
