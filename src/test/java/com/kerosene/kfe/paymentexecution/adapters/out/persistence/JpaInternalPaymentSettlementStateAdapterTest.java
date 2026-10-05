package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.audit.*;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.*;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.*;
import com.kerosene.kfe.adapters.out.persistence.model.messaging.*;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.*;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.*;
import com.kerosene.kfe.adapters.out.persistence.model.shared.*;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.*;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JpaInternalPaymentSettlementStateAdapterTest {
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final JpaInternalPaymentSettlementStateAdapter adapter = new JpaInternalPaymentSettlementStateAdapter(
            transactions, wallets, entityManager);
    private final KfeTransactionEntity tx = new KfeTransactionEntity();
    private final KfeWalletEntity source = new KfeWalletEntity();
    private final KfeWalletEntity destination = new KfeWalletEntity();
    private final PaymentExecutionId id = new PaymentExecutionId(tx.getId());

    @Test
    void locksByOwnerThenRefreshesAndMapsAuthoritativeWalletsAndAmounts() {
        ready();
        var snapshot = adapter.lockAndLoad(7L, id);
        snapshot.requireReadyFor(7L, id);
        assertThat(snapshot.sourceWalletId()).isEqualTo(source.getId());
        assertThat(snapshot.destinationWalletId()).isEqualTo(destination.getId());
        assertThat(snapshot.recipientUserId()).isEqualTo(8L);
        assertThat(snapshot.totalDebitSats()).isEqualTo(10_000L);
        assertThat(snapshot.receiverAmountSats()).isEqualTo(9_910L);
        var order = inOrder(transactions, entityManager, wallets);
        order.verify(transactions).findByIdAndUserIdForUpdate(tx.getId(), 7L);
        order.verify(entityManager).refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        order.verify(wallets).findByIdAndUserId(source.getId(), 7L);
        order.verify(wallets).findById(destination.getId());
        order.verifyNoMoreInteractions();
    }

    @Test
    void missingOrForeignExecutionDoesNotLookUpWallets() {
        assertThatThrownBy(() -> adapter.lockAndLoad(7L, id)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(wallets, entityManager);
    }

    @Test
    void ownerChangedDuringLockWaitIsRejectedBeforeWalletReads() {
        ready();
        doAnswer(invocation -> { tx.setUserId(8L); return null; })
                .when(entityManager).refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        assertThatThrownBy(() -> adapter.lockAndLoad(7L, id)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(wallets);
    }

    @Test
    void refreshedStateIsNotReplacedWithTheOldLockedProjection() {
        ready();
        doAnswer(invocation -> { tx.setStatus(KfeTransactionStatus.SETTLED); return null; })
                .when(entityManager).refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        var snapshot = adapter.lockAndLoad(7L, id);
        assertThatThrownBy(() -> snapshot.requireReadyFor(7L, id)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"source-owner", "source-id", "missing-source", "destination-id", "destination-owner", "missing-destination"})
    void rejectsInconsistentOrMissingWalletIdentity(String invalid) {
        ready();
        switch (invalid) {
            case "source-owner" -> source.setUserId(8L);
            case "source-id" -> source.setId(UUID.randomUUID());
            case "missing-source" -> when(wallets.findByIdAndUserId(tx.getSourceWalletId(), 7L)).thenReturn(Optional.empty());
            case "destination-id" -> destination.setId(UUID.randomUUID());
            case "destination-owner" -> destination.setUserId(null);
            case "missing-destination" -> when(wallets.findById(tx.getDestinationWalletId())).thenReturn(Optional.empty());
        }
        assertThatThrownBy(() -> adapter.lockAndLoad(7L, id)).isInstanceOf(IllegalArgumentException.class);
        verify(transactions, never()).save(any());
    }

    private void ready() {
        tx.setUserId(7L);
        tx.setRail(KfeRail.INTERNAL);
        tx.setDirection(KfeDirection.INTERNAL);
        tx.setStatus(KfeTransactionStatus.LOCKED);
        tx.setSourceWalletId(source.getId());
        tx.setDestinationWalletId(destination.getId());
        tx.setTotalDebitSats(10_000L);
        tx.setReceiverAmountSats(9_910L);
        source.setUserId(7L);
        destination.setUserId(8L);
        when(transactions.findByIdAndUserIdForUpdate(tx.getId(), 7L)).thenReturn(Optional.of(tx));
        when(wallets.findByIdAndUserId(source.getId(), 7L)).thenReturn(Optional.of(source));
        when(wallets.findById(destination.getId())).thenReturn(Optional.of(destination));
    }
}
