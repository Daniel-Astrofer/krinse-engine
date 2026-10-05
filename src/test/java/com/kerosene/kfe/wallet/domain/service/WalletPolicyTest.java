package com.kerosene.kfe.wallet.domain.service;

import com.kerosene.kfe.wallet.domain.exception.WalletRuleViolation;
import com.kerosene.kfe.wallet.domain.model.ColdPsbtRequest;
import com.kerosene.kfe.wallet.domain.model.Outpoint;
import com.kerosene.kfe.wallet.domain.model.UtxoSnapshot;
import com.kerosene.kfe.wallet.domain.model.WalletKind;
import com.kerosene.kfe.wallet.domain.model.WalletSnapshot;
import com.kerosene.kfe.wallet.domain.model.WalletStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletPolicyTest {
    private final UUID walletId = UUID.randomUUID();
    private final WalletSnapshot cold = new WalletSnapshot(
            walletId, 7L, WalletKind.WATCH_ONLY, WalletStatus.ACTIVE, "BTC", false,
            true, true, "tpub", "wpkh(tpub/0/*)");

    @Test
    void selectsAllLiveInputsWhenClientDoesNotSpecifyInputs() {
        var first = utxo("A", 0);
        var second = utxo("B", 1);

        assertThat(WalletPolicy.selectOwnedInputs(List.of(first, second), List.of()))
                .containsExactly(first, second);
    }

    @Test
    void rejectsPartiallyOwnedSelectionInsteadOfSilentlyDroppingForeignInputs() {
        var owned = new Outpoint("owned", 0);
        assertThatThrownBy(() -> WalletPolicy.selectOwnedInputs(
                List.of(utxo("owned", 0)),
                List.of(owned, new Outpoint("foreign", 1))))
                .isInstanceOf(WalletRuleViolation.class)
                .hasMessageContaining("not an owned live UTXO");
    }

    @Test
    void requiresTheExactOwnerAndActiveState() {
        assertThatThrownBy(() -> WalletPolicy.requireOwnedActive(cold, 8L, walletId))
                .isInstanceOf(WalletRuleViolation.class)
                .hasMessage("KFE wallet not found.");

        WalletSnapshot frozen = new WalletSnapshot(
                walletId, 7L, WalletKind.WATCH_ONLY, WalletStatus.FROZEN, "BTC", false,
                true, true, "tpub", "wpkh(tpub/0/*)");
        assertThatThrownBy(() -> WalletPolicy.requireOwnedActive(frozen, 7L, walletId))
                .isInstanceOf(WalletRuleViolation.class)
                .hasMessage("Wallet is not active.");
    }

    @Test
    void validatesColdPsbtBindingBeforeInfrastructure() {
        WalletPolicy.requireColdPsbtWallet(cold);
        assertThat(WalletPolicy.requireDestination(new ColdPsbtRequest(
                "tb1qdestination", 546L, 3, 2L, List.of())))
                .isEqualTo("tb1qdestination");

        assertThatThrownBy(() -> WalletPolicy.requireDestination(
                new ColdPsbtRequest("tb1qdestination", 545L, 3, 2L, List.of())))
                .isInstanceOf(WalletRuleViolation.class);
    }

    private static UtxoSnapshot utxo(String txid, int vout) {
        return new UtxoSnapshot(new Outpoint(txid, vout), 1_000L, "0014", "tb1qsource", 3);
    }
}
