package com.kerosene.kfe.wallet.domain.service;

import com.kerosene.kfe.wallet.domain.exception.WalletRuleViolation;
import com.kerosene.kfe.wallet.domain.model.WalletKind;
import com.kerosene.kfe.wallet.domain.model.WalletStatus;

/** Pure lifecycle decisions; persistence and quorum remain adapters. */
public final class WalletLifecyclePolicy {
    /** Maximum number of active or creating watch-only wallets allowed per owner. */
    private static final int MAX_WATCH_ONLY = 2;

    /** Prevents instantiation of the stateless lifecycle policy. */
    private WalletLifecyclePolicy() {
    }

    /** Enforces one active/creating wallet per custody kind, with a two-wallet watch-only cap. */
    public static void requireCapacity(WalletKind kind, long activeOrCreatingCount) {
        if (kind == null || activeOrCreatingCount < 0) {
            throw new WalletRuleViolation("Wallet kind and capacity are required.");
        }
        if (kind == WalletKind.WATCH_ONLY) {
            if (activeOrCreatingCount >= MAX_WATCH_ONLY) {
                throw new WalletRuleViolation("É permitido criar no máximo duas carteiras frias ativas.");
            }
            return;
        }
        if (activeOrCreatingCount > 0) {
            throw new WalletRuleViolation(
                    "Já existe uma carteira ativa ou em criação para este método de custódia.");
        }
    }

    /** Rejects a missing or blank user-facing wallet label. */
    public static void requireLabel(String label) {
        if (label == null || label.isBlank()) {
            throw new WalletRuleViolation("Wallet label is required.");
        }
    }

    /** Requires a non-archived wallet and a nonblank replacement label. */
    public static void requireUpdatable(WalletStatus status, String label) {
        if (status == WalletStatus.ARCHIVED) {
            throw new WalletRuleViolation("Archived wallets cannot be updated.");
        }
        requireLabel(label);
    }

    /** Prevents archival while creation or receive-address rotation is in progress. */
    public static void requireArchivable(WalletStatus status) {
        if (status == WalletStatus.CREATING || status == WalletStatus.ROTATING_ADDRESS) {
            throw new WalletRuleViolation("Wallet cannot be archived while it is being created or rotated.");
        }
    }

    /** Allows receive-address rotation only from the active lifecycle state. */
    public static void requireRotatable(WalletStatus status) {
        if (status != WalletStatus.ACTIVE) {
            throw new WalletRuleViolation("Wallet is not active.");
        }
    }
}
