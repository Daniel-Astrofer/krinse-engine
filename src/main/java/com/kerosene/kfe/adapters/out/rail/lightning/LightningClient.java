package com.kerosene.kfe.adapters.out.rail.lightning;

/**
 * Interface for Lightning Network node operations (LND, Core Lightning, BTCPay).
 */
public interface LightningClient {

    /**
     * Total outbound capacity (local balance) across all active channels.
     */
    long getLocalBalance();

    /**
     * Total inbound capacity (remote balance) across all active channels.
     */
    long getRemoteBalance();

    /**
     * Current total balance of the LN node hot wallet (including channel funds).
     */
    long getLightningNodeBalance();

    /**
     * System uptime percentage (0.0 to 1.0).
     */
    double getNodeUptime();

    /**
     * Latency to the Liquidity Service Provider (LSP) node in milliseconds.
     */
    long getLspLatency();

    /**
     * Total in-flight HTLCs across active channels, or {@code -1} if not probeable.
     */
    default int pendingHtlcCount() {
        return -1;
    }

    /**
     * Remote pubkeys of peers that currently have at least one pending HTLC.
     * Empty when none or not probeable.
     */
    default java.util.Set<String> peersWithPendingHtlcs() {
        return java.util.Set.of();
    }
}
