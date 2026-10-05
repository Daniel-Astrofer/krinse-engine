package com.kerosene.kfe.wallet.domain.model;

/**
 * Result of probing on-chain balance for a wallet. Carries quality metadata so writers
 * can refuse low-quality absolute updates (partial scans, mempool-blind totals, zero spikes).
 *
 * @param sats observed balance in satoshis
 * @param quality evidence quality used by synchronization policy
 * @param authoritative whether the source can establish an absolute current balance
 * @param outpointCount number of outputs represented by the observation
 * @param source stable source label for diagnostics and audit
 */
public record ChainProbeResult(
        long sats,
        ProbeQuality quality,
        boolean authoritative,
        int outpointCount,
        String source) {

    /** Normalizes absent metadata and rejects negative balances. */
    public ChainProbeResult {
        if (sats < 0L) {
            throw new IllegalArgumentException("probe sats must be >= 0");
        }
        if (quality == null) {
            quality = ProbeQuality.UNKNOWN;
        }
        if (outpointCount < 0) {
            outpointCount = 0;
        }
        if (source == null || source.isBlank()) {
            source = "unknown";
        }
    }

    /** Creates authoritative evidence that accounts for live mempool spends. */
    public static ChainProbeResult liveMempoolAware(long sats, int outpointCount, String source) {
        return new ChainProbeResult(sats, ProbeQuality.LIVE_MEMPOOL_AWARE, true, outpointCount, source);
    }

    /** Creates confirmed-only evidence that deliberately is not marked authoritative. */
    public static ChainProbeResult confirmedUtxoSet(long sats, int outpointCount, String source) {
        return new ChainProbeResult(sats, ProbeQuality.CONFIRMED_UTXO_SET, false, outpointCount, source);
    }

    /** Creates a non-authoritative best-effort delta observation. */
    public static ChainProbeResult optimisticDelta(long sats, String source) {
        return new ChainProbeResult(sats, ProbeQuality.OPTIMISTIC_DELTA, false, 0, source);
    }

    /** Creates an incomplete observation that balance policy must not use for a write. */
    public static ChainProbeResult unknown(String source) {
        return new ChainProbeResult(0L, ProbeQuality.UNKNOWN, false, 0, source);
    }
}
