package com.kerosene.kfe.wallet.domain.service;

import com.kerosene.kfe.wallet.domain.exception.WalletRuleViolation;
import com.kerosene.kfe.wallet.domain.model.Outpoint;
import com.kerosene.kfe.wallet.domain.model.SignedPsbt;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignedPsbtBindingPolicyTest {
    @Test
    void rejectsInputSubstitution() {
        assertThatThrownBy(() -> SignedPsbtBindingPolicy.requireMatches(
                new SignedPsbt(
                        List.of(new Outpoint("foreign", 0)),
                        List.of(new SignedPsbt.Output("tb1qdest", 1_000L))),
                "tb1qdest",
                1_000L,
                List.of(new Outpoint("owned", 0))))
                .isInstanceOf(WalletRuleViolation.class)
                .hasMessageContaining("not part of the approved workflow");
    }

    @Test
    void requiresExactApprovedPayment() {
        assertThatThrownBy(() -> SignedPsbtBindingPolicy.requireMatches(
                new SignedPsbt(
                        List.of(new Outpoint("owned", 0)),
                        List.of(new SignedPsbt.Output("tb1qdest", 999L))),
                "tb1qdest",
                1_000L,
                List.of(new Outpoint("owned", 0))))
                .isInstanceOf(WalletRuleViolation.class)
                .hasMessageContaining("does not pay");
    }
}
