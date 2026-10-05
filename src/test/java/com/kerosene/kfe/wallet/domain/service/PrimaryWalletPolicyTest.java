package com.kerosene.kfe.wallet.domain.service;

import com.kerosene.kfe.wallet.domain.model.WalletKind;
import com.kerosene.kfe.wallet.domain.model.WalletStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrimaryWalletPolicyTest {
    @Test
    void onlyActiveSpendableInternalWalletIsReady() {
        assertThat(PrimaryWalletPolicy.isReady(
                WalletKind.INTERNAL, WalletStatus.ACTIVE, true)).isTrue();
        assertThat(PrimaryWalletPolicy.isReady(
                WalletKind.INTERNAL, WalletStatus.CREATING, true)).isFalse();
        assertThat(PrimaryWalletPolicy.isReady(
                WalletKind.WATCH_ONLY, WalletStatus.ACTIVE, true)).isFalse();
        assertThat(PrimaryWalletPolicy.isReady(
                WalletKind.INTERNAL, WalletStatus.ACTIVE, false)).isFalse();
    }
}
