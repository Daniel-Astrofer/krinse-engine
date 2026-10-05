package com.kerosene.kfe.paymentexecution.domain.exception;

import com.kerosene.kfe.paymentexecution.domain.model.SettlementGateEvaluation;

/**
 * Thrown when the binary settlement gate product is 0.
 * Callers must not move balance after this exception.
 */
public class SettlementGateRejectedException extends IllegalStateException {

    private final SettlementGateEvaluation result;

    public SettlementGateRejectedException(SettlementGateEvaluation result) {
        super(messageFor(result));
        this.result = result;
    }

    public SettlementGateEvaluation result() {
        return result;
    }

    private static String messageFor(SettlementGateEvaluation result) {
        if (result == null || result.failedFlags().isEmpty()) {
            return "KFE settlement gate rejected the transaction.";
        }
        return "KFE settlement gate rejected the transaction. Failed flags: "
                + result.failedFlags().stream().map(Enum::name).toList();
    }
}
