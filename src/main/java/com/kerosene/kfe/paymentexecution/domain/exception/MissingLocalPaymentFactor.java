package com.kerosene.kfe.paymentexecution.domain.exception;

/** Missing local credential; transport status and payload are adapter responsibilities. */
public final class MissingLocalPaymentFactor extends RuntimeException {
    public MissingLocalPaymentFactor() {
        super("PIN do aplicativo obrigatorio para transacoes internas KFE e onchain custodial.");
    }
}
