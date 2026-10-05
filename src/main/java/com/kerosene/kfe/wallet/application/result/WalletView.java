package com.kerosene.kfe.wallet.application.result;

import com.kerosene.kfe.wallet.domain.model.WalletKind;
import com.kerosene.kfe.wallet.domain.model.WalletStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Stable application view used by inbound adapters; it contains no API DTOs or key material.
 *
 * @param id wallet identity
 * @param kind custody and behavior category
 * @param status current lifecycle status
 * @param label user-editable label
 * @param walletName configured wallet name
 * @param walletTypeDescription human-readable wallet type summary
 * @param asset asset represented by the wallet, normally BTC
 * @param spendable whether policy currently allows spending from this wallet
 * @param xpubConfigured whether public derivation material is available
 * @param mpcKeyConfigured whether custody key provisioning has been completed
 * @param activeAddress current receive address when one is available
 * @param createdAt creation timestamp
 * @param updatedAt most recent persisted update timestamp
 */
public record WalletView(
        UUID id,
        WalletKind kind,
        WalletStatus status,
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
