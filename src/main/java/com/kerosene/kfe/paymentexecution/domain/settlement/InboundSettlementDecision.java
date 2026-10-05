package com.kerosene.kfe.paymentexecution.domain.settlement;

/** Fail-closed result of binding an inbound observation to persisted execution state. */
public record InboundSettlementDecision(Outcome outcome, String code) {

    public enum Outcome {
        SETTLE,
        IDEMPOTENT,
        RECONCILE,
        REJECT
    }

    public boolean allowsSettlement() {
        return outcome == Outcome.SETTLE;
    }

    public boolean isIdempotent() {
        return outcome == Outcome.IDEMPOTENT;
    }

    public boolean shouldRecordReconciliation() {
        return outcome == Outcome.RECONCILE;
    }
}
