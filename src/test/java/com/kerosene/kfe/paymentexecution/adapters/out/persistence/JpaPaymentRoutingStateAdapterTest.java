package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.audit.*;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.*;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.*;
import com.kerosene.kfe.adapters.out.persistence.model.messaging.*;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.*;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.*;
import com.kerosene.kfe.adapters.out.persistence.model.shared.*;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.*;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JpaPaymentRoutingStateAdapterTest {
    private final KfeTransactionRepository repository = mock(KfeTransactionRepository.class);
    private final EntityManager em = mock(EntityManager.class);
    private final JpaPaymentRoutingStateAdapter adapter = new JpaPaymentRoutingStateAdapter(repository, em);
    private final KfeTransactionEntity tx = new KfeTransactionEntity();
    private final PaymentExecutionId id = new PaymentExecutionId(tx.getId());

    @Test
    void scopedLockRefreshesStaleStateAndMapsExactPersistedFields() {
        ready();
        doAnswer(invocation -> { tx.setStatus(KfeTransactionStatus.EXECUTING); tx.setReceiverAmountSats(8_000L); return null; })
                .when(em).refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        var result = adapter.lockAndLoad(7L, id);
        assertThat(result).isEqualTo(new PaymentRoutingSnapshot(id, 7L, ExecutionStatus.EXECUTING, PaymentRail.LIGHTNING,
                PaymentDirection.OUTBOUND, new IdempotencyKey(" key "), tx.getSourceWalletId(), tx.getDestinationWalletId(),
                10_000L, 8_000L, 100L, 10_100L, "reference", "memo", "proposal"));
        assertThatThrownBy(() -> result.requireReadyFor(7L, id)).isInstanceOf(IllegalStateException.class);
        var order = inOrder(repository, em);
        order.verify(repository).findByIdAndUserIdForUpdate(id.value(), 7L);
        order.verify(em).refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "id", "owner", "null-owner"})
    void cannotLoadAnotherUsersOrMismatchedExecution(String invalid) {
        ready();
        var requestedId = invalid.equals("id") ? new PaymentExecutionId(UUID.randomUUID()) : id;
        when(repository.findByIdAndUserIdForUpdate(requestedId.value(), 7L))
                .thenReturn(invalid.equals("missing") ? Optional.empty() : Optional.of(tx));
        doAnswer(invocation -> {
            if (invalid.equals("owner")) { tx.setUserId(8L); }
            if (invalid.equals("null-owner")) { tx.setUserId(null); }
            return null;
        }).when(em).refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        assertThatThrownBy(() -> adapter.lockAndLoad(7L, requestedId)).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void flushUsesScopedRowAndRequiresPersistedExecutingState() {
        ready();
        when(repository.findByIdAndUserId(id.value(), 7L)).thenReturn(Optional.of(tx));
        assertThatThrownBy(() -> adapter.flush(7L, id)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).saveAndFlush(any());
        tx.setStatus(KfeTransactionStatus.EXECUTING);
        adapter.flush(7L, id);
        verify(repository).saveAndFlush(tx);
        verifyNoInteractions(em);
    }

    @Test
    void invalidSelectorsFailBeforePersistenceAndRefreshFailureDoesNotFallBack() {
        assertThatThrownBy(() -> adapter.lockAndLoad(0L, id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.flush(7L, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, em);
        ready();
        var failure = new IllegalStateException("refresh unavailable");
        doThrow(failure).when(em).refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        assertThatThrownBy(() -> adapter.lockAndLoad(7L, id)).isSameAs(failure);
    }

    private void ready() {
        tx.setUserId(7L); tx.setStatus(KfeTransactionStatus.LOCKED); tx.setRail(KfeRail.LIGHTNING); tx.setDirection(KfeDirection.OUTBOUND);
        tx.setIdempotencyKey(" key "); tx.setSourceWalletId(UUID.randomUUID()); tx.setDestinationWalletId(UUID.randomUUID());
        tx.setGrossAmountSats(10_000L); tx.setReceiverAmountSats(9_910L); tx.setNetworkFeeSats(100L); tx.setTotalDebitSats(10_100L);
        tx.setExternalReference("reference"); tx.setMemo("memo"); tx.setQuorumProposalHash("proposal");
        when(repository.findByIdAndUserIdForUpdate(id.value(), 7L)).thenReturn(Optional.of(tx));
    }
}
