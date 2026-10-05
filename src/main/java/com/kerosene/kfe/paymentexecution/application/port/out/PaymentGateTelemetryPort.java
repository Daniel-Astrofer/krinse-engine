package com.kerosene.kfe.paymentexecution.application.port.out;

/** Metrics boundary for settlement, liquidity, and solvency gate decisions. */
public interface PaymentGateTelemetryPort {
    /** Records whether the complete settlement gate passed. */
    /** @param passed aggregate AND result across all required flags */
    void recordSettlementGate(boolean passed);
    /** Records a liquidity-specific rejection reason. */
    /** @param reason stable gate reason without secrets or raw provider payloads */
    void recordLiquidityReject(String reason);
    /** Records an unavailable or failed solvency probe. */
    /** @param reason bounded operational reason without raw exception payloads */
    void recordSolvencyFailure(String reason);
}
