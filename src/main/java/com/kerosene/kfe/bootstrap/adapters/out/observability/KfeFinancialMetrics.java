package com.kerosene.kfe.bootstrap.adapters.out.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer counters and gauges for KFE transaction lifecycle observability.
 *
 * <p>Safe when MeterRegistry is absent (tests / minimal profiles).
 */
@Component
public class KfeFinancialMetrics {

    /** Optional Micrometer registry; null keeps minimal/test profiles operational. */
    private final MeterRegistry registry;

    /** Current total amount held in locked transaction buckets, in satoshis. */
    private final AtomicLong lockedSats = new AtomicLong(0L);
    /** Number of notification outbox entries currently awaiting delivery. */
    private final AtomicLong notificationPending = new AtomicLong(0L);
    /** Absolute difference between ledger and observed chain balances, in satoshis. */
    private final AtomicLong balanceDivergenceSats = new AtomicLong(0L);
    /** Amount in currently in-flight Lightning payments, in satoshis. */
    private final AtomicLong lightningInflightSats = new AtomicLong(0L);
    /** Number of idempotency claims currently being processed. */
    private final AtomicLong idempotencyInProgress = new AtomicLong(0L);
    /** Adapter authentication configuration encoded as 1 for enabled and 0 for disabled. */
    private final AtomicLong adapterAuthEnabled = new AtomicLong(1L);
    /** Configured Bitcoin network ordinal exposed using the documented 0/1/2 mapping. */
    private final AtomicLong configuredNetwork = new AtomicLong(0L);
    /** Runtime Bitcoin network ordinal exposed using the documented 0/1/2 mapping. */
    private final AtomicLong runningNetwork = new AtomicLong(0L);
    /** Epoch seconds when the oldest currently stuck transaction entered its present state. */
    private final AtomicLong stuckSinceEpochSeconds = new AtomicLong(0L);

    /**
     * Captures an optional metrics registry and registers gauges when it is available.
     *
     * @param registry provider for the application MeterRegistry
     */
    public KfeFinancialMetrics(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry.getIfAvailable();
        if (this.registry != null) {
            registerGauges();
        }
    }

    /** Registers gauges backed by atomic values so metric reads observe the latest published state. */
    private void registerGauges() {
        Gauge.builder("kfe.locked.sats", lockedSats, AtomicLong::get)
                .description("Total locked satoshis across all transactions")
                .register(registry);
        Gauge.builder("kfe.notification.pending", notificationPending, AtomicLong::get)
                .description("Pending notification outbox entries")
                .register(registry);
        Gauge.builder("kfe.balance.divergence_sats", balanceDivergenceSats, AtomicLong::get)
                .description("Absolute ledger vs chain balance divergence in satoshis")
                .register(registry);
        Gauge.builder("kfe.lightning.inflight_sats", lightningInflightSats, AtomicLong::get)
                .description("In-flight Lightning payment total in satoshis")
                .register(registry);
        Gauge.builder("kfe.idempotency.in_progress", idempotencyInProgress, AtomicLong::get)
                .description("In-flight idempotency claims")
                .register(registry);
        Gauge.builder("kfe.adapter.auth_enabled", adapterAuthEnabled, AtomicLong::get)
                .description("1 if adapter auth is enabled, 0 otherwise")
                .register(registry);
        Gauge.builder("kfe.configured_network", configuredNetwork, AtomicLong::get)
                .description("Configured Bitcoin network (0=mainnet, 1=testnet, 2=regtest)")
                .register(registry);
        Gauge.builder("kfe.running_network", runningNetwork, AtomicLong::get)
                .description("Running Bitcoin network (0=mainnet, 1=testnet, 2=regtest)")
                .register(registry);
        Gauge.builder("kfe.transactions.stuck_since_seconds", stuckSinceEpochSeconds, AtomicLong::get)
                .description("Unix epoch seconds since oldest stuck transaction entered current status")
                .register(registry);
    }

    // --- Transaction lifecycle counters ---

    /**
     * Increments the transaction lifecycle counter with rail, direction, and status dimensions.
     * Missing dimensions are represented by {@code UNKNOWN}; no-op when metrics are unavailable.
     *
     * @param rail payment rail label
     * @param direction transaction direction label
     * @param status lifecycle status label
     */
    public void recordTransaction(String rail, String direction, String status) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.transactions.total")
                .description("Transaction lifecycle counter per rail, direction, and status")
                .tags(Tags.of(
                        "rail", rail != null ? rail : "UNKNOWN",
                        "direction", direction != null ? direction : "UNKNOWN",
                        "status", status != null ? status : "UNKNOWN"))
                .register(registry)
                .increment();
    }

    /**
     * Increments the manual-reconciliation-required counter for a payment rail.
     *
     * @param rail affected payment rail, defaulting to UNKNOWN when null
     */
    public void recordReconciliationRequired(String rail) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.reconciliation.required")
                .description("Events requiring manual financial reconciliation")
                .tags(Tags.of("rail", rail != null ? rail : "UNKNOWN"))
                .register(registry)
                .increment();
    }

    /**
     * Increments the conflicted-transaction counter for a payment rail.
     *
     * @param rail affected payment rail, defaulting to UNKNOWN when null
     */
    public void recordConflicted(String rail) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.conflicted")
                .description("Conflicted transaction events")
                .tags(Tags.of("rail", rail != null ? rail : "UNKNOWN"))
                .register(registry)
                .increment();
    }

    /**
     * Increments the blockchain-reorganization counter for a payment rail.
     *
     * @param rail affected payment rail, defaulting to UNKNOWN when null
     */
    public void recordReorg(String rail) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.reorg")
                .description("Blockchain reorg events detected")
                .tags(Tags.of("rail", rail != null ? rail : "UNKNOWN"))
                .register(registry)
                .increment();
    }

    /**
     * Increments the Bitcoin Core transaction-not-found counter for a payment rail.
     *
     * @param rail affected payment rail, defaulting to UNKNOWN when null
     */
    public void recordNetworkNotFound(String rail) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.network.not_found")
                .description("Transaction not found by Bitcoin Core")
                .tags(Tags.of("rail", rail != null ? rail : "UNKNOWN"))
                .register(registry)
                .increment();
    }

    // --- Idempotency metrics ---

    /** Increments the idempotency conflict counter when a key is reused for incompatible input. */
    public void recordIdempotencyConflict() {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.idempotency.conflict")
                .description("Idempotency key conflicts detected")
                .register(registry)
                .increment();
    }

    // --- Notification outbox metrics ---

    /**
     * Records a notification moved to the dead-letter state.
     *
     * @param eventType notification event type dimension, defaulting to UNKNOWN
     */
    public void recordNotificationDeadLetter(String eventType) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.notification.dead_letter")
                .description("Dead-letter notification outbox entries")
                .tags(Tags.of("eventType", eventType != null ? eventType : "UNKNOWN"))
                .register(registry)
                .increment();
    }

    /**
     * Records an event durably added to the notification outbox.
     *
     * @param eventType notification event type dimension, defaulting to UNKNOWN
     */
    public void recordNotificationEnqueued(String eventType) {
        if (registry == null) return;
        Counter.builder("kfe.notification.enqueued")
                .description("Durable financial notification events appended to the outbox")
                .tags(Tags.of("eventType", eventType != null ? eventType : "UNKNOWN"))
                .register(registry)
                .increment();
    }

    /**
     * Records successful delivery of a notification outbox event.
     *
     * @param eventType notification event type dimension, defaulting to UNKNOWN
     */
    public void recordNotificationDelivered(String eventType) {
        if (registry == null) return;
        Counter.builder("kfe.notification.delivered")
                .description("Financial notification events delivered from the outbox")
                .tags(Tags.of("eventType", eventType != null ? eventType : "UNKNOWN"))
                .register(registry)
                .increment();
    }

    // --- Gauge setters ---

    /** @param sats current locked transaction total; negative values are clamped to zero */
    public void setLockedSats(long sats) {
        lockedSats.set(Math.max(0L, sats));
    }

    /** @param count current pending outbox count; negative values are clamped to zero */
    public void setNotificationPending(long count) {
        notificationPending.set(Math.max(0L, count));
    }

    /** @param sats absolute ledger/chain divergence in satoshis; signed values are preserved */
    public void setBalanceDivergenceSats(long sats) {
        balanceDivergenceSats.set(sats);
    }

    /** @param sats in-flight Lightning total in satoshis; negative values are clamped to zero */
    public void setLightningInflightSats(long sats) {
        lightningInflightSats.set(Math.max(0L, sats));
    }

    /** @param count active idempotency claims; negative values are clamped to zero */
    public void setIdempotencyInProgress(long count) {
        idempotencyInProgress.set(Math.max(0L, count));
    }

    /** @param enabled whether adapter authentication is enabled, exported as 1 or 0 */
    public void setAdapterAuthEnabled(boolean enabled) {
        adapterAuthEnabled.set(enabled ? 1L : 0L);
    }

    /** @param networkId configured network ordinal: 0 mainnet, 1 testnet, 2 regtest */
    public void setConfiguredNetwork(int networkId) {
        configuredNetwork.set(networkId);
    }

    /** @param networkId observed running network ordinal: 0 mainnet, 1 testnet, 2 regtest */
    public void setRunningNetwork(int networkId) {
        runningNetwork.set(networkId);
    }

    /** @param epochSeconds Unix epoch seconds for oldest stuck transaction; negative values become zero */
    public void setStuckSinceEpochSeconds(long epochSeconds) {
        stuckSinceEpochSeconds.set(Math.max(0L, epochSeconds));
    }
}
