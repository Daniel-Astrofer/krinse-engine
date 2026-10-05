package com.kerosene.kfe.adapters.out.integration.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.kerosene.common.financial.operations.FinancialMpcKeyPort;
import com.kerosene.common.vaultmesh.settlement.VaultMeshDepositInfo;
import com.kerosene.common.vaultmesh.settlement.VaultMeshSettlementPort;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class KfeVaultMeshMpcKeyAdapterTest {

    private static final UUID WALLET_ID = UUID.fromString("22222222-3333-4444-5555-666666666666");

    @Test
    void keygenWalletReturnsXonlyPubkeyWhenPresent() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        when(port.getUsersDepositAddress()).thenReturn(new VaultMeshDepositInfo(
                "tb1pqqqqp399et0xe0j3xehqlenme4egz9y7dznlk9rn2nperql6n8nsgt27ds", // valid bech32m
                "tr(...)#descriptor",
                "taproot",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
                "testnet"));
        ObjectProvider<VaultMeshSettlementPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);

        FinancialMpcKeyPort adapter = new KfeVaultMeshMpcKeyAdapter(provider);

        assertThat(adapter.keygenWallet(WALLET_ID, 42L))
                .isEqualTo("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210");
    }

    @Test
    void keygenWalletFallsBackToOutputPubkeyWithValidFormat() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        when(port.getUsersDepositAddress()).thenReturn(new VaultMeshDepositInfo(
                "tb1pfallback",
                null,
                "taproot",
                "aabbccddeeff0011aabbccddeeff0011aabbccddeeff0011aabbccddeeff0011",
                null,
                "testnet"));
        ObjectProvider<VaultMeshSettlementPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);

        FinancialMpcKeyPort adapter = new KfeVaultMeshMpcKeyAdapter(provider);

        assertThat(adapter.keygenWallet(WALLET_ID, 42L))
                .isEqualTo("aabbccddeeff0011aabbccddeeff0011aabbccddeeff0011aabbccddeeff0011");
    }

    @Test
    void keygenWalletFailsWhenPubkeysAreNotValidHex() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        // xonly_pubkey is null, output_pubkey is not 64 hex chars — should fail
        when(port.getUsersDepositAddress()).thenReturn(new VaultMeshDepositInfo(
                "tb1pfallback",
                null,
                "taproot",
                "short",
                null,
                "testnet"));
        ObjectProvider<VaultMeshSettlementPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);

        FinancialMpcKeyPort adapter = new KfeVaultMeshMpcKeyAdapter(provider);

        assertThatThrownBy(() -> adapter.keygenWallet(WALLET_ID, 42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned an empty public key");
    }

    @Test
    void keygenWalletFailsClosedWhenMeshDepositUnavailable() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        when(port.getUsersDepositAddress()).thenReturn(null);
        ObjectProvider<VaultMeshSettlementPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(port);

        FinancialMpcKeyPort adapter = new KfeVaultMeshMpcKeyAdapter(provider);

        assertThatThrownBy(() -> adapter.keygenWallet(WALLET_ID, 42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("USERS deposit key unavailable");
    }

    @Test
    void keygenWalletFailsClosedWhenSettlementPortMissing() {
        ObjectProvider<VaultMeshSettlementPort> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        FinancialMpcKeyPort adapter = new KfeVaultMeshMpcKeyAdapter(provider);

        assertThatThrownBy(() -> adapter.keygenWallet(WALLET_ID, 42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("settlement port unavailable");
    }
}
