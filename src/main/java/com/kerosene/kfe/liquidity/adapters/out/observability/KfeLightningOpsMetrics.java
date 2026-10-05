package com.kerosene.kfe.liquidity.adapters.out.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Micrometer counters for Lightning settlement / channel rebalance ops.
 */
@Component
public class KfeLightningOpsMetrics {

    private final MeterRegistry registry;

    public KfeLightningOpsMetrics(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry.getIfAvailable();
    }

    public void recordRebalance(String result, String provider) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.lightning.rebalance")
                .description("Channel rebalance job outcomes")
                .tags(Tags.of(
                        "result", result != null ? result : "unknown",
                        "provider", provider != null ? provider : "unknown"))
                .register(registry)
                .increment();
    }

    public void recordSettlementGate(String result) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.settlement.gate")
                .description("Binary settlement gate pass/fail")
                .tags(Tags.of("result", result != null ? result : "unknown"))
                .register(registry)
                .increment();
    }

    public void recordLiquidityReject(String reason) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.lightning.liquidity_reject")
                .description("Settlement rejects for insufficient Lightning liquidity")
                .tags(Tags.of("reason", reason != null ? reason : "unknown"))
                .register(registry)
                .increment();
    }

    public void recordCapacity(String result, String reason) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.lightning.capacity")
                .description("Dead-man channel capacity controller outcomes")
                .tags(Tags.of(
                        "result", result != null ? result : "unknown",
                        "reason", reason != null ? reason : "unknown"))
                .register(registry)
                .increment();
    }
}
