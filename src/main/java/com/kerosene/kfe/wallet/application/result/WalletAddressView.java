package com.kerosene.kfe.wallet.application.result;

import com.kerosene.kfe.wallet.domain.model.AddressRole;

import java.time.Instant;
import java.util.UUID;

/**
 * Stable application projection of an issued or retired wallet address.
 *
 * @param id persisted address identity
 * @param walletId wallet that owns the address
 * @param address public Bitcoin address string
 * @param role address purpose such as receive or change
 * @param status lifecycle status maintained by the address adapter
 * @param derivationPath derivation path when available
 * @param derivationIndex child index when available
 * @param providerReference external provider reference, not private key material
 * @param createdAt timestamp when the address was persisted
 * @param retiredAt timestamp when it stopped being active, or null while active
 */
public record WalletAddressView(
        UUID id,
        UUID walletId,
        String address,
        AddressRole role,
        String status,
        String derivationPath,
        Integer derivationIndex,
        String providerReference,
        Instant createdAt,
        Instant retiredAt) {
}
