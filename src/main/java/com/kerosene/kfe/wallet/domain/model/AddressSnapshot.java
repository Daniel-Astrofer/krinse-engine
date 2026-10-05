package com.kerosene.kfe.wallet.domain.model;

import java.util.UUID;

/**
 * Immutable address state used by wallet policy and chain discovery.
 *
 * @param id persisted address identity
 * @param walletId owning wallet identity
 * @param address public Bitcoin address; normalized by trimming whitespace
 * @param role receive, change, or monitor purpose
 * @param active whether this address participates in current wallet operations
 * @param derivationPath optional key derivation path
 * @param derivationIndex optional child index within the derivation path
 */
public record AddressSnapshot(
        UUID id,
        UUID walletId,
        String address,
        AddressRole role,
        boolean active,
        String derivationPath,
        Integer derivationIndex) {

    /** Validates identities and normalizes the public address string. */
    public AddressSnapshot {
        if (id == null || walletId == null) {
            throw new IllegalArgumentException("Address identity is required.");
        }
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("Address is required.");
        }
        address = address.trim();
    }
}
