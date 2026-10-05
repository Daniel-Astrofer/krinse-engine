package com.kerosene.kfe.wallet.adapters.in.observation;

import com.kerosene.kfe.ledger.adapters.in.reconciliation.KfeOnchainBalanceSyncService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeColdWalletReactiveRefreshServiceTest {

    private KfeColdWalletObservationService coldObs;
    private KfeMonitoredChainAddressIndex addressIndex;
    private KfeColdWalletReactiveRefreshService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        coldObs = mock(KfeColdWalletObservationService.class);
        addressIndex = mock(KfeMonitoredChainAddressIndex.class);
        ObjectProvider<KfeColdWalletObservationService> coldProvider = mock(ObjectProvider.class);
        ObjectProvider<KfeCustodialDepositObservationService> custodialProvider = mock(ObjectProvider.class);
        ObjectProvider<KfeOnchainBalanceSyncService> balanceProvider = mock(ObjectProvider.class);
        when(coldProvider.getIfAvailable()).thenReturn(coldObs);
        when(custodialProvider.getIfAvailable()).thenReturn(null);
        when(balanceProvider.getIfAvailable()).thenReturn(null);
        service = new KfeColdWalletReactiveRefreshService(
                coldProvider,
                custodialProvider,
                balanceProvider,
                addressIndex,
                200L);
    }

    @AfterEach
    void tearDown() {
        // nothing to close — daemon scheduler dies with JVM
    }

    @Test
    void debouncesWalletTouchesIntoObserveCalls() throws Exception {
        UUID walletId = UUID.randomUUID();
        service.onWalletsTouched(Set.of(walletId));
        service.onWalletsTouched(Set.of(walletId));

        verify(coldObs, timeout(2000).times(1)).observeWallet(walletId);
    }

    @Test
    void newBlockRefreshesAllIndexedColdWallets() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(addressIndex.allColdWalletIds()).thenReturn(Set.of(a, b));

        service.onNewBlock();

        verify(coldObs, timeout(2000)).observeWallet(a);
        verify(coldObs, timeout(2000)).observeWallet(b);
        assertThat(addressIndex.allColdWalletIds()).containsExactlyInAnyOrder(a, b);
    }
}
