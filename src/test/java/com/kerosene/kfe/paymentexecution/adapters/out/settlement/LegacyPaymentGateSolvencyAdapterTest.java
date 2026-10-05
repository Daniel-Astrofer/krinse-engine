package com.kerosene.kfe.paymentexecution.adapters.out.settlement;

import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceEntity;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceId;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementBalanceSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementSolvencySnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementWalletRole;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.ledger.adapters.in.KfeProofOfReservesService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyPaymentGateSolvencyAdapterTest {
    private final KfeBalanceRepository balances = mock(KfeBalanceRepository.class);
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final KfeProofOfReservesService reserves = mock(KfeProofOfReservesService.class);
    private final LegacyPaymentGateSolvencyAdapter adapter = new LegacyPaymentGateSolvencyAdapter(balances, wallets, reserves);

    @ParameterizedTest
    @CsvSource({"CUSTODIAL_ONCHAIN,CUSTOMER", "INTERNAL,CUSTOMER", "SYSTEM_PROFIT,SYSTEM_PROFIT",
            "WATCH_ONLY,OTHER", "SYSTEM_FUNDS,OTHER"})
    void mapsWalletRoleAndEveryAmountWithoutMutatingTheBalance(KfeWalletKind kind, SettlementWalletRole role) {
        var row = balance(UUID.randomUUID());
        when(balances.findAll()).thenReturn(List.of(row));
        when(wallets.findKindsByIds(Set.of(row.getId().getWalletId())))
                .thenReturn(List.<Object[]>of(new Object[]{row.getId().getWalletId(), kind}));

        var result = adapter.loadBalances();

        assertThat(result).containsExactly(new SettlementBalanceSnapshot(role, 101L, 102L, 103L, 104L, 105L));
        row.setAvailableSats(999L);
        assertThat(result.getFirst().availableSats()).isEqualTo(101L);
        assertThatThrownBy(() -> result.clear()).isInstanceOf(UnsupportedOperationException.class);
        verify(balances).findAll();
        verify(wallets).findKindsByIds(Set.of(row.getId().getWalletId()));
        verifyNoMoreInteractions(balances, wallets);
        verifyNoInteractions(reserves);
    }

    @Test
    void batchesKindsOnceSkipsRowsWithoutWalletIdentityAndKeepsUnknownRolesExcluded() {
        var customer = balance(UUID.randomUUID());
        var unknown = balance(UUID.randomUUID());
        var absent = new KfeBalanceEntity();
        var noWallet = balance(null);
        when(balances.findAll()).thenReturn(List.of(customer, unknown, absent, noWallet));
        List<Object[]> kinds = new ArrayList<>();
        kinds.add(new Object[]{customer.getId().getWalletId(), KfeWalletKind.INTERNAL});
        kinds.add(new Object[]{unknown.getId().getWalletId(), null});
        kinds.add(new Object[]{"invalid UUID", KfeWalletKind.SYSTEM_PROFIT});
        when(wallets.findKindsByIds(Set.of(customer.getId().getWalletId(), unknown.getId().getWalletId()))).thenReturn(kinds);

        assertThat(adapter.loadBalances()).extracting(SettlementBalanceSnapshot::role)
                .containsExactly(SettlementWalletRole.CUSTOMER, SettlementWalletRole.OTHER);

        verify(wallets).findKindsByIds(Set.of(customer.getId().getWalletId(), unknown.getId().getWalletId()));
        verifyNoMoreInteractions(wallets);
    }

    @Test
    void emptyIdentitySetDoesNotIssueWalletQuery() {
        when(balances.findAll()).thenReturn(List.of(new KfeBalanceEntity(), balance(null)));
        assertThat(adapter.loadBalances()).isEmpty();
        verifyNoInteractions(wallets, reserves);
    }

    @Test
    void delegatesTheExistingCachedAssetProxyAndReturnsAllSolvencyEvidence() {
        when(reserves.isEnabled()).thenReturn(true);
        when(reserves.computeSnapshot(500L, 10L, 0L, 1000L, 1000L, 0L, null))
                .thenReturn(new KfeProofOfReservesService.SolvencySnapshot(510L, 500L, 10L, 0L,
                        1000L, 1000L, 0L, 255L, 1.96, 1.2, true, null, Instant.EPOCH));

        assertThat(adapter.isEnabled()).isTrue();
        assertThat(adapter.computeSnapshot(500L, 10L, 1000L))
                .isEqualTo(new SettlementSolvencySnapshot(true, 1.96, 1.2, 510L, 1000L, 255L));

        verify(reserves).isEnabled();
        verify(reserves).computeSnapshot(500L, 10L, 0L, 1000L, 1000L, 0L, null);
        verifyNoMoreInteractions(reserves);
        verifyNoInteractions(balances, wallets);
    }

    @Test
    void doesNotReplacePersistenceFailureWithAnEmptyApparentlySolventDataset() {
        var failure = new IllegalStateException("balances unavailable");
        when(balances.findAll()).thenThrow(failure);
        assertThatThrownBy(adapter::loadBalances).isSameAs(failure);
        verifyNoInteractions(wallets, reserves);
    }

    private KfeBalanceEntity balance(UUID id) {
        var row = new KfeBalanceEntity(); row.setId(new KfeBalanceId(id, "BTC"));
        row.setAvailableSats(101L); row.setPendingSats(102L); row.setLockedSats(103L);
        row.setAutoHoldSats(104L); row.setObservedSats(105L); return row;
    }
}
