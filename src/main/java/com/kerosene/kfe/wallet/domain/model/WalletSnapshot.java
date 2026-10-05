package com.kerosene.kfe.wallet.domain.model;

import java.util.UUID;

/**
 * Immutable policy-facing snapshot of wallet identity, lifecycle, and public chain configuration.
 *
 * @param id wallet identity
 * @param ownerId owning account identity, which must be positive
 * @param kind custody and wallet behavior category
 * @param status lifecycle state
 * @param asset normalized uppercase asset ticker
 * @param spendable whether wallet policy currently permits spending
 * @param xpubConfigured whether extended public key derivation material is configured
 * @param descriptorConfigured whether an output descriptor is configured
 * @param xpub optional extended public key; private keys must never be placed here
 * @param descriptor optional public output descriptor
 */
public record WalletSnapshot(
        UUID id,
        long ownerId,
        WalletKind kind,
        WalletStatus status,
        String asset,
        boolean spendable,
        boolean xpubConfigured,
        boolean descriptorConfigured,
        String xpub,
        String descriptor) {

    /** Validates required identity/state and normalizes asset and optional public material. */
    public WalletSnapshot {
        if (id == null || ownerId <= 0 || kind == null || status == null) {
            throw new IllegalArgumentException("Wallet identity and state are required.");
        }
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("Wallet asset is required.");
        }
        asset = asset.trim().toUpperCase(java.util.Locale.ROOT);
        xpub = xpub == null || xpub.isBlank() ? null : xpub.trim();
        descriptor = descriptor == null || descriptor.isBlank() ? null : descriptor.trim();
    }

    /** Returns whether the wallet is currently available for ordinary wallet operations. */
    public boolean isActive() {
        return status == WalletStatus.ACTIVE;
    }

    /** Returns whether this is a watch-only wallet requiring an offline signing workflow. */
    public boolean isCold() {
        return kind == WalletKind.WATCH_ONLY;
    }
}
