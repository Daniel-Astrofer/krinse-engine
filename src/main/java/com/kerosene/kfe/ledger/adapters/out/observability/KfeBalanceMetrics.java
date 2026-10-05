package com.kerosene.kfe.ledger.adapters.out.observability;

import com.kerosene.kfe.wallet.domain.model.ProbeQuality;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;

/**
 * Records low-cardinality Micrometer counters for balance consistency and probe policy outcomes.
 * Every method is a no-op when no {@link MeterRegistry} is available, supporting minimal profiles.
 */
@Component
public class KfeBalanceMetrics {

    /** Optional registry receiving the balance/reconciliation meters. */
    private final MeterRegistry registry;

    /**
     * Captures the optional registry once at bean construction.
     *
     * @param registry provider for the active Micrometer registry
     */
    public KfeBalanceMetrics(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry.getIfAvailable();
    }

    /** Counts a chain observation outcome by quality, result, and wallet kind. */
    public void recordProbe(ProbeQuality quality, String result, KfeWalletKind kind) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.cold.probe")
                .description("Cold/on-chain observed probe outcomes")
                .tags(Tags.of(
                        "quality", quality != null ? quality.name() : "UNKNOWN",
                        "result", result != null ? result : "unknown",
                        "kind", kind != null ? kind.name() : "UNKNOWN"))
                .register(registry)
                .increment();
    }

    /** Counts outpoints discarded by the mempool spend filter, ignoring nonpositive values. */
    public void recordMempoolFiltered(int outpointCount) {
        if (registry == null || outpointCount <= 0) {
            return;
        }
        Counter.builder("kfe.cold.mempool_filtered_outpoints")
                .description("Outpoints dropped by mempool spend filter")
                .register(registry)
                .increment(outpointCount);
    }

    /**
     * Counts a positive absolute drift event and accumulates its satoshi magnitude by wallet kind.
     *
     * @param kind wallet custody kind, tagged UNKNOWN when absent
     * @param absDriftSats absolute observed-versus-ledger difference
     */
    public void recordDrift(KfeWalletKind kind, long absDriftSats) {
        if (registry == null || absDriftSats <= 0L) {
            return;
        }
        Counter.builder("kfe.balance.drift_events")
                .description("Custodial dual-ledger drift detections above threshold")
                .tags(Tags.of("kind", kind != null ? kind.name() : "UNKNOWN"))
                .register(registry)
                .increment();
        // Distribution-style counter of absolute drift magnitude (sats).
        Counter.builder("kfe.balance.drift_sats_total")
                .tags(Tags.of("kind", kind != null ? kind.name() : "UNKNOWN"))
                .register(registry)
                .increment(absDriftSats);
    }

    /** Counts a websocket balance publication by the selected bucket label. */
    public void recordWsPublish(String bucket) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.ws.balance_publish")
                .tags(Tags.of("bucket", bucket != null ? bucket : "PRIMARY"))
                .register(registry)
                .increment();
    }

    /** Counts a reconciliation incident where locked funds have no valid timely transaction. */
    public void recordLockedStuck() {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.balance.locked_stuck")
                .description("Wallets with locked funds and long-running EXECUTING/RECON txs")
                .register(registry)
                .increment();
    }

    /**
     * Counts a dual-path credit skipped because another path already settled the movement or won a race.
     *
     * @param path credit path label, tagged unknown when absent
     */
    public void recordDualCreditSkip(String path) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.credit.dual_skip")
                .description("Available credit skipped because another path already settled the tx")
                .tags(Tags.of("path", path != null ? path : "unknown"))
                .register(registry)
                .increment();
    }

    /** Counts an idempotent fee-credit operation that was skipped because it was already settled. */
    public void recordFeeIdempotentSkip() {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.fee.skip_idempotent")
                .description("Kerosene fee credit skipped (already settled)")
                .register(registry)
                .increment();
    }

    /** Counts a ZMQ/optimistic balance update deferred by probe-quality policy. */
    public void recordOptimisticDeferred(String reason) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.zmq.optimistic_deferred")
                .description("ZMQ/optimistic observed write deferred by quality policy")
                .tags(Tags.of("reason", reason != null ? reason : "unknown"))
                .register(registry)
                .increment();
    }

    /** Counts a probe update deferred to preserve a fresher or higher-quality observation. */
    public void recordProbeMonotonicDefer(String reason) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.cold.probe_monotonic_defer")
                .description("Observed write deferred to protect fresher/higher-quality probe")
                .tags(Tags.of("reason", reason != null ? reason : "unknown"))
                .register(registry)
                .increment();
    }
}
