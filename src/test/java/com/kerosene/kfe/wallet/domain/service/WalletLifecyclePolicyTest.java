package com.kerosene.kfe.wallet.domain.service;

import com.kerosene.kfe.wallet.domain.exception.WalletRuleViolation;
import com.kerosene.kfe.wallet.domain.model.WalletKind;
import com.kerosene.kfe.wallet.domain.model.WalletStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletLifecyclePolicyTest {
    @Test
    void enforcesCustodyCapacity() {
        assertThatThrownBy(() -> WalletLifecyclePolicy.requireCapacity(WalletKind.WATCH_ONLY, 2))
                .isInstanceOf(WalletRuleViolation.class);
        assertThatThrownBy(() -> WalletLifecyclePolicy.requireCapacity(WalletKind.INTERNAL, 1))
                .isInstanceOf(WalletRuleViolation.class);
    }

    @Test
    void blocksUnsafeTransitions() {
        assertThatThrownBy(() -> WalletLifecyclePolicy.requireArchivable(WalletStatus.CREATING))
                .isInstanceOf(WalletRuleViolation.class);
        assertThatThrownBy(() -> WalletLifecyclePolicy.requireRotatable(WalletStatus.FROZEN))
                .isInstanceOf(WalletRuleViolation.class);
        assertThatThrownBy(() -> WalletLifecyclePolicy.requireUpdatable(WalletStatus.ARCHIVED, "label"))
                .isInstanceOf(WalletRuleViolation.class);
    }
}
