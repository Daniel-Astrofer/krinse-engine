package com.kerosene.kfe.adapters.in.http.dto.wallet;

import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;

import java.time.Instant;
import java.util.UUID;

/** Wallet metadata and operational capabilities returned by wallet APIs.
 * @param id stable wallet identifier
 * @param kind wallet category used by routing and custody policy
 * @param status lifecycle state controlling whether the wallet may be used
 * @param label user-assigned display label
 * @param walletName canonical wallet name used for lookup
 * @param walletTypeDescription human-readable description of the wallet category
 * @param asset asset ticker represented by this wallet
 * @param spendable whether current policy allows outgoing operations
 * @param xpubConfigured whether an extended public key is available for derivation
 * @param mpcKeyConfigured whether the wallet's MPC key reference is configured
 * @param activeAddress current receiving address, when one is assigned
 * @param createdAt wallet creation timestamp
 * @param updatedAt most recent wallet modification timestamp
 */
public record KfeWalletResponse(
        UUID id,
        KfeWalletKind kind,
        KfeWalletStatus status,
        String label,
        String walletName,
        String walletTypeDescription,
        String asset,
        boolean spendable,
        boolean xpubConfigured,
        boolean mpcKeyConfigured,
        String activeAddress,
        Instant createdAt,
        Instant updatedAt) {
}
