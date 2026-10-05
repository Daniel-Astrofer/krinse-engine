package com.kerosene.kfe.wallet.domain.service;

import com.kerosene.kfe.wallet.domain.model.WalletKind;
import com.kerosene.kfe.wallet.domain.model.WalletStatus;

/** Readiness rule used by signup provisioning and internal recovery paths. */
public final class PrimaryWalletPolicy {
    /** Prevents instantiation of the stateless readiness policy. */
    private PrimaryWalletPolicy() {
    }

    /** Requires the internal wallet to be active and explicitly marked spendable. */
    public static boolean isReady(WalletKind kind, WalletStatus status, boolean spendable) {
        return kind == WalletKind.INTERNAL && status == WalletStatus.ACTIVE && spendable;
    }
}
