package com.kerosene.kfe.wallet.domain.model;

/**
 * Quality of an on-chain balance probe. Higher ordinal is not strictly better — policy
 * in the balance synchronization adapter decides when a write is allowed.
 */
public enum ProbeQuality {
    /**
     * Absolute sum of live UTXOs after mempool spend filter + change absorb (Electrum parity).
     * Authoritative for {@code WATCH_ONLY}.
     */
    LIVE_MEMPOOL_AWARE,

    /**
     * Confirmed UTXO set only ({@code scantxoutset} total). Ignores mempool spends —
     * must not overwrite a good live/optimistic cold balance.
     */
    CONFIRMED_UTXO_SET,

    /**
     * Best-effort delta from ZMQ before a full scan. May be refused when collapsing to zero
     * or when amounts are incomplete.
     */
    OPTIMISTIC_DELTA,

    /** Probe failed or incomplete — never write. */
    UNKNOWN
}
