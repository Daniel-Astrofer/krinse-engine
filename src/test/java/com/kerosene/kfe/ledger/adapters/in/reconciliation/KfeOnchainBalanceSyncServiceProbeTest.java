package com.kerosene.kfe.ledger.adapters.in.reconciliation;

import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.adapters.out.rail.onchain.BlockchainClient;
import com.kerosene.kfe.wallet.adapters.out.bitcoin.KfeWalletDescriptorResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KfeOnchainBalanceSyncServiceProbeTest {

    @Mock
    private KfeWalletRepository walletRepository;
    @Mock
    private KfeWalletAddressRepository addressRepository;
    @Mock
    private KfeWalletDescriptorResolver descriptorResolver;
    @Mock
    private BlockchainClient blockchainClient;

    private KfeOnchainBalanceSyncService service;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<BlockchainClient> clientProvider = mock(ObjectProvider.class);

        service = new KfeOnchainBalanceSyncService(
                walletRepository,
                addressRepository,
                null,
                clientProvider,
                null,
                mock(TransactionTemplate.class),
                descriptorResolver,
                null,
                50,
                1000,
                120L
        );
    }

    @Test
    void probeChainBalanceSatsThrowsWhenAddressScanFailsWithBusyError() {
        UUID walletId = UUID.randomUUID();
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(walletId);
        wallet.setKind(KfeWalletKind.CUSTODIAL_ONCHAIN);

        KfeWalletAddressEntity addr = new KfeWalletAddressEntity();
        addr.setWalletId(walletId);
        addr.setAddress("tb1qtestaddress12345");
        when(addressRepository.findByWalletIdOrderByCreatedAtDesc(walletId))
                .thenReturn(List.of(addr));

        when(blockchainClient.getUnspentBalanceForAddresses(anyList())).thenReturn(0L);
        when(blockchainClient.getConfirmedBalanceForAddress("tb1qtestaddress12345"))
                .thenThrow(new IllegalStateException("Bitcoin UTXO scan is busy; observation must be retried"));

        assertThatThrownBy(() -> service.probeChainBalanceSats(blockchainClient, wallet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scan is busy");
    }

    @Test
    void probeChainBalanceSatsThrowsWhenNoAddressesAndDescriptorFails() {
        UUID walletId = UUID.randomUUID();
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(walletId);
        wallet.setKind(KfeWalletKind.CUSTODIAL_ONCHAIN);

        when(addressRepository.findByWalletIdOrderByCreatedAtDesc(walletId))
                .thenReturn(List.of());
        when(descriptorResolver.resolveReceiveDescriptor(wallet))
                .thenReturn("wpkh(tpubD6NzVbkrYhZ4X.../0/*)");
        when(blockchainClient.getConfirmedBalanceForDescriptor(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("Bitcoin UTXO scan did not complete successfully"));

        assertThatThrownBy(() -> service.probeChainBalanceSats(blockchainClient, wallet))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not complete");
    }

    @Test
    void probeChainBalanceSatsUsesAddressWhenDescriptorFails() {
        UUID walletId = UUID.randomUUID();
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(walletId);
        wallet.setKind(KfeWalletKind.CUSTODIAL_ONCHAIN);

        KfeWalletAddressEntity addr = new KfeWalletAddressEntity();
        addr.setWalletId(walletId);
        addr.setAddress("tb1qtestaddress12345");
        when(addressRepository.findByWalletIdOrderByCreatedAtDesc(walletId))
                .thenReturn(List.of(addr));

        when(blockchainClient.getUnspentBalanceForAddresses(List.of("tb1qtestaddress12345"))).thenReturn(0L);
        when(blockchainClient.getConfirmedBalanceForAddress("tb1qtestaddress12345")).thenReturn(100_000L);

        when(descriptorResolver.resolveReceiveDescriptor(wallet))
                .thenReturn("wpkh(tpubD6NzVbkrYhZ4X.../0/*)");
        when(blockchainClient.getConfirmedBalanceForDescriptor(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("Descriptor scan timeout"));

        long sats = service.probeChainBalanceSats(blockchainClient, wallet);
        assertThat(sats).isEqualTo(100_000L);
    }

    @Test
    void probeChainBalanceSatsReturnsZeroWhenWalletHasNoAddressesAndNoDescriptor() {
        UUID walletId = UUID.randomUUID();
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(walletId);
        wallet.setKind(KfeWalletKind.CUSTODIAL_ONCHAIN);

        when(addressRepository.findByWalletIdOrderByCreatedAtDesc(walletId))
                .thenReturn(List.of());
        when(descriptorResolver.resolveReceiveDescriptor(wallet))
                .thenReturn(null);

        long sats = service.probeChainBalanceSats(blockchainClient, wallet);
        assertThat(sats).isEqualTo(0L);
    }
}
