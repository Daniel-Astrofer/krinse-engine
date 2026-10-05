package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JpaPaymentWalletLookupAdapterTest {
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final KfeWalletAddressRepository addresses = mock(KfeWalletAddressRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final JpaPaymentWalletLookupAdapter adapter = new JpaPaymentWalletLookupAdapter(wallets, addresses, entityManager);

    @Test
    void locksByOwnerThenRefreshesBeforeProjectingState() {
        var wallet = wallet();
        when(wallets.findByIdAndUserIdForUpdate(wallet.getId(), 7L)).thenReturn(Optional.of(wallet));
        doAnswer(invocation -> { wallet.setStatus(KfeWalletStatus.ARCHIVED); return null; })
                .when(entityManager).refresh(wallet, LockModeType.PESSIMISTIC_WRITE);

        var snapshot = adapter.lockOwnedSource(7L, wallet.getId()).orElseThrow();

        assertThat(snapshot.active()).isFalse();
        assertThat(snapshot.usable()).isFalse();
        assertThat(snapshot.id()).isEqualTo(wallet.getId());
        assertThat(snapshot.userId()).isEqualTo(7L);
        var order = inOrder(wallets, entityManager);
        order.verify(wallets).findByIdAndUserIdForUpdate(wallet.getId(), 7L);
        order.verify(entityManager).refresh(wallet, LockModeType.PESSIMISTIC_WRITE);
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "owner"})
    void rejectsIdentityDivergenceAfterRefresh(String field) {
        var wallet = wallet();
        UUID requested = wallet.getId();
        when(wallets.findByIdAndUserIdForUpdate(requested, 7L)).thenReturn(Optional.of(wallet));
        doAnswer(invocation -> {
            if (field.equals("id")) { wallet.setId(UUID.randomUUID()); }
            else { wallet.setUserId(8L); }
            return null;
        }).when(entityManager).refresh(wallet, LockModeType.PESSIMISTIC_WRITE);

        assertThatThrownBy(() -> adapter.lockOwnedSource(7L, requested))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Source KFE wallet not found.");
    }

    @Test
    void missingOrForeignSourceDoesNotRefreshOrFallBackToUnscopedLookup() {
        UUID id = UUID.randomUUID();
        assertThat(adapter.lockOwnedSource(7L, id)).isEmpty();
        verify(wallets).findByIdAndUserIdForUpdate(id, 7L);
        verifyNoMoreInteractions(wallets);
        verifyNoInteractions(entityManager, addresses);
    }

    @Test
    void inboundUsesScopedSqlWithoutFallingBackToGlobalLookup() {
        var wallet = wallet();
        when(wallets.findByIdAndUserId(wallet.getId(), 7L)).thenReturn(Optional.of(wallet));
        assertThat(adapter.findOwnedDestination(7L, wallet.getId()).orElseThrow().id()).isEqualTo(wallet.getId());
        assertThat(adapter.findOwnedDestination(8L, wallet.getId())).isEmpty();
        verify(wallets).findByIdAndUserId(wallet.getId(), 7L);
        verify(wallets).findByIdAndUserId(wallet.getId(), 8L);
        verifyNoMoreInteractions(wallets);
        verifyNoInteractions(entityManager);
    }

    @ParameterizedTest
    @EnumSource(KfeWalletStatus.class)
    @NullSource
    void onlyActiveStateIsUsable(KfeWalletStatus status) {
        var wallet = wallet();
        wallet.setStatus(status);
        when(wallets.findById(wallet.getId())).thenReturn(Optional.of(wallet));
        assertThat(adapter.findById(wallet.getId()).orElseThrow().usable()).isEqualTo(status == KfeWalletStatus.ACTIVE);
    }

    @ParameterizedTest
    @EnumSource(KfeWalletKind.class)
    @NullSource
    void watchOnlyAndUnknownKindsAreNotSpendable(KfeWalletKind kind) {
        var wallet = wallet();
        wallet.setKind(kind);
        when(wallets.findById(wallet.getId())).thenReturn(Optional.of(wallet));
        assertThat(adapter.findById(wallet.getId()).orElseThrow().usable())
                .isEqualTo(kind != null && kind != KfeWalletKind.WATCH_ONLY);
    }

    @Test
    void falseSpendableIsNotOverriddenByAnActiveWalletKind() {
        var wallet = wallet();
        wallet.setSpendable(false);
        when(wallets.findById(wallet.getId())).thenReturn(Optional.of(wallet));
        assertThat(adapter.findById(wallet.getId()).orElseThrow().usable()).isFalse();
    }

    @Test
    void addressLookupPreservesCaseInsensitiveRepositoryContractAndUsesStoredWallet() {
        var wallet = wallet();
        var address = new KfeWalletAddressEntity();
        address.setWalletId(wallet.getId());
        when(addresses.findFirstByAddressIgnoreCase("MixedCaseAddress")).thenReturn(Optional.of(address));
        when(wallets.findById(wallet.getId())).thenReturn(Optional.of(wallet));
        assertThat(adapter.findByAddress("MixedCaseAddress").orElseThrow().id()).isEqualTo(wallet.getId());
        verify(addresses).findFirstByAddressIgnoreCase("MixedCaseAddress");
        verify(wallets).findById(wallet.getId());
    }

    @Test
    void unknownAddressHasNoWalletLookup() {
        assertThat(adapter.findByAddress("unknown")).isEmpty();
        verifyNoInteractions(wallets, entityManager);
    }

    @Test
    void preservesNewestFirstOrderAndDoesNotExposeWalletSecrets() {
        var newest = wallet();
        var older = wallet();
        newest.setXpub("secret-xpub");
        newest.setDescriptor("secret-descriptor");
        newest.setQuorumPolicyHash("secret-policy");
        when(wallets.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(newest, older));
        var snapshots = adapter.findForUserNewestFirst(7L);
        assertThat(snapshots).extracting(snapshot -> snapshot.id()).containsExactly(newest.getId(), older.getId());
        assertThat(snapshots.toString()).doesNotContain("secret-xpub", "secret-descriptor", "secret-policy");
        verify(wallets).findByUserIdOrderByCreatedAtDesc(7L);
        verifyNoMoreInteractions(wallets);
    }

    private static KfeWalletEntity wallet() {
        var wallet = new KfeWalletEntity();
        wallet.setUserId(7L);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        wallet.setKind(KfeWalletKind.INTERNAL);
        wallet.setSpendable(true);
        return wallet;
    }
}
