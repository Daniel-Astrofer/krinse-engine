package com.kerosene.kfe.paymentexecution.domain.exception;

public final class IdempotencyKeyConflict extends IllegalStateException {
    public IdempotencyKeyConflict() {
        super("Idempotency key was reused with a different transaction payload.");
    }
}
