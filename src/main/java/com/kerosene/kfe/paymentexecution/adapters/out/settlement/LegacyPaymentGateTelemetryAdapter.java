package com.kerosene.kfe.paymentexecution.adapters.out.settlement;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentGateTelemetryPort;
import com.kerosene.kfe.liquidity.adapters.out.observability.KfeCapacitySignalStore;
import com.kerosene.kfe.liquidity.adapters.out.observability.KfeLightningOpsMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class LegacyPaymentGateTelemetryAdapter implements PaymentGateTelemetryPort {
    private static final Logger log = LoggerFactory.getLogger(LegacyPaymentGateTelemetryAdapter.class);
    private final ObjectProvider<KfeLightningOpsMetrics> metrics;
    private final ObjectProvider<KfeCapacitySignalStore> signals;
    public LegacyPaymentGateTelemetryAdapter(ObjectProvider<KfeLightningOpsMetrics> metrics,
            ObjectProvider<KfeCapacitySignalStore> signals) { this.metrics = metrics; this.signals = signals; }

    @Override public void recordSettlementGate(boolean passed) {
        var available = metrics.getIfAvailable();
        if (available != null) { available.recordSettlementGate(passed ? "pass" : "fail"); }
    }
    @Override public void recordLiquidityReject(String reason) {
        var availableSignals = signals.getIfAvailable();
        if (availableSignals != null) { availableSignals.recordLiquidityReject(); }
        var availableMetrics = metrics.getIfAvailable();
        if (availableMetrics != null) { availableMetrics.recordLiquidityReject(reason != null ? reason : "V_LIQUIDEZ"); }
    }
    @Override public void recordSolvencyFailure(String reason) { log.error("PoR solvency check failed: {}", reason); }
}
