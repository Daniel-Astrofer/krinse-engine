package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class JpaPaymentFundsReservationStateAdapterTest {
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final JpaPaymentFundsReservationStateAdapter adapter =
            new JpaPaymentFundsReservationStateAdapter(transactions, entityManager);
    private final KfeTransactionEntity transaction = new KfeTransactionEntity();
    private final PaymentExecutionId id = new PaymentExecutionId(transaction.getId());

    @Test
    void scopedLockThenRefreshMapsOnlyAuthoritativeReservationInputs() {
        ready();

        var snapshot = adapter.lockAndLoad(7L, id);

        snapshot.requireReadyFor(7L, id);
        assertThat(snapshot.executionId()).isEqualTo(id);
        assertThat(snapshot.userId()).isEqualTo(7L);
        assertThat(snapshot.status()).isEqualTo(ExecutionStatus.QUORUM_SYNC);
        assertThat(snapshot.rail()).isEqualTo(PaymentRail.LIGHTNING);
        assertThat(snapshot.direction()).isEqualTo(PaymentDirection.OUTBOUND);
        assertThat(snapshot.sourceWalletId()).isEqualTo(transaction.getSourceWalletId());
        assertThat(snapshot.totalDebitSats()).isEqualTo(10_150L);
        assertThat(snapshot.proposalHash()).isEqualTo("quorum-proposal");
        assertThat(snapshot.quorumAckCount()).isEqualTo(3);
        var order = inOrder(transactions, entityManager);
        order.verify(transactions).findByIdAndUserIdForUpdate(id.value(), 7L);
        order.verify(entityManager).refresh(transaction, LockModeType.PESSIMISTIC_WRITE);
        order.verifyNoMoreInteractions();
    }

    @Test
    void unknownOrForeignExecutionDoesNotRefreshAnyEntity() {
        assertThatThrownBy(() -> adapter.lockAndLoad(7L, id))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE transaction not found.");
        verify(transactions).findByIdAndUserIdForUpdate(id.value(), 7L);
        verifyNoInteractions(entityManager);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void invalidAuthenticatedUserIsRejectedBeforePersistence(long userId) {
        assertThatThrownBy(() -> adapter.lockAndLoad(userId, id)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(transactions, entityManager);
    }

    @Test
    void missingExecutionIdIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> adapter.lockAndLoad(7L, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(transactions, entityManager);
    }

    @Test
    void repositoryReturningDifferentExecutionCannotAuthorizeReservation() {
        ready();
        var requestedId = new PaymentExecutionId(UUID.randomUUID());
        when(transactions.findByIdAndUserIdForUpdate(requestedId.value(), 7L)).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> adapter.lockAndLoad(7L, requestedId))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE transaction not found.");
        verify(transactions, never()).save(any());
    }

    @Test
    void ownerChangedWhileWaitingForLockIsRejectedAfterRefresh() {
        ready();
        doAnswer(invocation -> { transaction.setUserId(8L); return null; })
                .when(entityManager).refresh(transaction, LockModeType.PESSIMISTIC_WRITE);

        assertThatThrownBy(() -> adapter.lockAndLoad(7L, id))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE transaction not found.");
        verify(transactions, never()).save(any());
    }

    @Test
    void missingOwnerAfterRefreshIsRejectedWithoutUnboxingNull() {
        ready();
        doAnswer(invocation -> { transaction.setUserId(null); return null; })
                .when(entityManager).refresh(transaction, LockModeType.PESSIMISTIC_WRITE);

        assertThatThrownBy(() -> adapter.lockAndLoad(7L, id))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE transaction not found.");
    }

    @Test
    void refreshedFinancialFieldsReplaceEarlierManagedSnapshot() {
        ready();
        UUID currentSourceId = UUID.randomUUID();
        doAnswer(invocation -> {
            transaction.setStatus(KfeTransactionStatus.LOCKED);
            transaction.setRail(KfeRail.ONCHAIN);
            transaction.setDirection(KfeDirection.INBOUND);
            transaction.setSourceWalletId(currentSourceId);
            transaction.setTotalDebitSats(12_300L);
            transaction.setQuorumProposalHash("new-proposal");
            transaction.setQuorumAckCount(4);
            return null;
        }).when(entityManager).refresh(transaction, LockModeType.PESSIMISTIC_WRITE);

        var snapshot = adapter.lockAndLoad(7L, id);

        assertThat(snapshot.status()).isEqualTo(ExecutionStatus.LOCKED);
        assertThat(snapshot.rail()).isEqualTo(PaymentRail.ONCHAIN);
        assertThat(snapshot.direction()).isEqualTo(PaymentDirection.INBOUND);
        assertThat(snapshot.sourceWalletId()).isEqualTo(currentSourceId);
        assertThat(snapshot.totalDebitSats()).isEqualTo(12_300L);
        assertThat(snapshot.proposalHash()).isEqualTo("new-proposal");
        assertThat(snapshot.quorumAckCount()).isEqualTo(4);
        assertThatThrownBy(() -> snapshot.requireReadyFor(7L, id)).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(KfeTransactionStatus.class)
    void mapsEveryPersistedExecutionStatusWithoutPretendingItIsEligible(KfeTransactionStatus status) {
        ready();
        transaction.setStatus(status);
        assertThat(adapter.lockAndLoad(7L, id).status()).isEqualTo(ExecutionStatus.valueOf(status.name()));
    }

    @ParameterizedTest
    @CsvSource({"INTERNAL,INTERNAL", "ONCHAIN,OUTBOUND", "ONCHAIN,INBOUND", "LIGHTNING,OUTBOUND", "LIGHTNING,INBOUND"})
    void mapsRailAndDirectionWithoutDerivingThemFromCallerMetadata(KfeRail rail, KfeDirection direction) {
        ready();
        transaction.setRail(rail);
        transaction.setDirection(direction);
        var snapshot = adapter.lockAndLoad(7L, id);
        assertThat(snapshot.rail()).isEqualTo(PaymentRail.valueOf(rail.name()));
        assertThat(snapshot.direction()).isEqualTo(PaymentDirection.valueOf(direction.name()));
    }

    @Test
    void refreshFailurePropagatesWithoutPersistingOrReturningOldState() {
        ready();
        var failure = new IllegalStateException("refresh unavailable");
        doThrow(failure).when(entityManager).refresh(transaction, LockModeType.PESSIMISTIC_WRITE);
        assertThatThrownBy(() -> adapter.lockAndLoad(7L, id)).isSameAs(failure);
        verify(transactions, never()).save(any());
    }

    private void ready() {
        transaction.setUserId(7L);
        transaction.setStatus(KfeTransactionStatus.QUORUM_SYNC);
        transaction.setRail(KfeRail.LIGHTNING);
        transaction.setDirection(KfeDirection.OUTBOUND);
        transaction.setSourceWalletId(UUID.randomUUID());
        transaction.setTotalDebitSats(10_150L);
        transaction.setQuorumProposalHash("quorum-proposal");
        transaction.setQuorumAckCount(3);
        when(transactions.findByIdAndUserIdForUpdate(id.value(), 7L)).thenReturn(Optional.of(transaction));
    }
}
