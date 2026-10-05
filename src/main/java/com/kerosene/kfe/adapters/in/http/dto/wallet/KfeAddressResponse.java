package com.kerosene.kfe.adapters.in.http.dto.wallet;

import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressRole;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressStatus;

import java.time.Instant;
import java.util.UUID;

/** Wallet address entry and its derivation and lifecycle metadata.
 * @param id address record identifier
 * @param walletId wallet that owns the address
 * @param address public receiving address
 * @param role address purpose, such as receive or change
 * @param status whether the address is active or retired
 * @param derivationPath HD path used to derive the address, when available
 * @param derivationIndex child index used for derivation, when available
 * @param providerReference external provider reference for provisioned addresses
 * @param createdAt address creation timestamp
 * @param retiredAt retirement timestamp, or {@code null} while active
 */
public record KfeAddressResponse(
        UUID id,
        UUID walletId,
        String address,
        KfeWalletAddressRole role,
        KfeWalletAddressStatus status,
        String derivationPath,
        Integer derivationIndex,
        String providerReference,
        Instant createdAt,
        Instant retiredAt) {
}
