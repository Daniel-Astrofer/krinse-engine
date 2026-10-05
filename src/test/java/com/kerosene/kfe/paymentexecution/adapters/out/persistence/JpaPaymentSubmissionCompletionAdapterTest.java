package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeTransactionResponse;
import com.kerosene.kfe.adapters.out.persistence.model.audit.*;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.*;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.*;
import com.kerosene.kfe.adapters.out.persistence.model.messaging.*;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.*;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.*;
import com.kerosene.kfe.adapters.out.persistence.model.shared.*;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.*;
import com.kerosene.kfe.paymentexecution.adapters.legacy.LegacyPaymentExecutionResultMapper;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JpaPaymentSubmissionCompletionAdapterTest {
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final EntityManager em = mock(EntityManager.class);
    private final KfeResponseMapper mapper = mock(KfeResponseMapper.class);
    private final JpaPaymentSubmissionCompletionAdapter adapter =
            new JpaPaymentSubmissionCompletionAdapter(transactions, em, mapper);
    private final KfeTransactionEntity tx = new KfeTransactionEntity();
    private final PaymentExecutionId id = new PaymentExecutionId(tx.getId());

    @Test
    void lockScopesAndRefreshesThePersistedCompletionSnapshot() {
        ready();
        doAnswer(invocation -> { tx.setStatus(KfeTransactionStatus.SETTLED); return null; })
                .when(em).refresh(tx, LockModeType.PESSIMISTIC_WRITE);

        var result = adapter.lockAndLoad(7L, id);

        assertThat(result).isEqualTo(new PaymentSubmissionCompletionSnapshot(id, 7L, new IdempotencyKey(" key "),
                ExecutionStatus.SETTLED, PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, tx.getDestinationWalletId()));
        var order = inOrder(transactions, em);
        order.verify(transactions).findByIdAndUserIdForUpdate(id.value(), 7L);
        order.verify(em).refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "id", "owner", "null-owner"})
    void rejectsMissingOrForeignStateAfterWaitingForTheLock(String invalid) {
        ready();
        var requestedId = invalid.equals("id") ? new PaymentExecutionId(UUID.randomUUID()) : id;
        when(transactions.findByIdAndUserIdForUpdate(requestedId.value(), 7L))
                .thenReturn(invalid.equals("missing") ? Optional.empty() : Optional.of(tx));
        doAnswer(invocation -> {
            if (invalid.equals("owner")) { tx.setUserId(8L); }
            if (invalid.equals("null-owner")) { tx.setUserId(null); }
            return null;
        }).when(em).refresh(tx, LockModeType.PESSIMISTIC_WRITE);

        assertThatThrownBy(() -> adapter.lockAndLoad(7L, requestedId)).isInstanceOf(IllegalArgumentException.class);
        verify(transactions, never()).save(any());
        verifyNoInteractions(mapper);
    }

    @Test
    void savesAnUnchangedOwnerScopedRowAndPreservesTheLegacyProjection() {
        ready();
        var expected = snapshot();
        var response = response();
        when(transactions.save(tx)).thenReturn(tx);
        when(mapper.toTransactionResponse(tx)).thenReturn(response);

        var result = adapter.saveAndProject(expected);

        assertThat(result).isEqualTo(LegacyPaymentExecutionResultMapper.toResult(response));
        var order = inOrder(transactions, mapper);
        order.verify(transactions).findByIdAndUserId(id.value(), 7L);
        order.verify(transactions).save(tx);
        order.verify(mapper).toTransactionResponse(tx);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(em);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "owner", "null-owner", "id", "key", "status", "rail", "direction", "destination"})
    void refusesAnyCompletionBoundaryMutationWithoutDiscardingIt(String changed) {
        ready();
        var expected = snapshot();
        switch (changed) {
            case "missing" -> when(transactions.findByIdAndUserId(id.value(), 7L)).thenReturn(Optional.empty());
            case "owner" -> tx.setUserId(8L);
            case "null-owner" -> tx.setUserId(null);
            case "id" -> org.springframework.test.util.ReflectionTestUtils.setField(tx, "id", UUID.randomUUID());
            case "key" -> tx.setIdempotencyKey("different-key");
            case "status" -> tx.setStatus(KfeTransactionStatus.FAILED);
            case "rail" -> tx.setRail(KfeRail.ONCHAIN);
            case "direction" -> tx.setDirection(KfeDirection.INBOUND);
            case "destination" -> tx.setDestinationWalletId(UUID.randomUUID());
            default -> throw new AssertionError(changed);
        }

        assertThatThrownBy(() -> adapter.saveAndProject(expected)).isInstanceOfAny(
                IllegalArgumentException.class, IllegalStateException.class);

        verify(transactions, never()).save(any());
        verifyNoInteractions(mapper, em);
    }

    @Test
    void invalidSelectorsDoNotReadAndFailedRefreshDoesNotFallBack() {
        assertThatThrownBy(() -> adapter.lockAndLoad(0L, id)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.lockAndLoad(7L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.saveAndProject(null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(transactions, mapper, em);
        ready();
        var failure = new IllegalStateException("refresh failed");
        doThrow(failure).when(em).refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        assertThatThrownBy(() -> adapter.lockAndLoad(7L, id)).isSameAs(failure);
        verify(transactions, never()).save(any());
    }

    @Test
    void failedProjectionPropagatesAfterSave() {
        ready();
        when(transactions.save(tx)).thenReturn(tx);
        var failure = new IllegalStateException("projection failed");
        when(mapper.toTransactionResponse(tx)).thenThrow(failure);
        assertThatThrownBy(() -> adapter.saveAndProject(snapshot())).isSameAs(failure);
        verify(transactions).save(tx);
        verifyNoInteractions(em);
    }

    private void ready() {
        tx.setUserId(7L);
        tx.setIdempotencyKey(" key ");
        tx.setStatus(KfeTransactionStatus.EXECUTING);
        tx.setRail(KfeRail.LIGHTNING);
        tx.setDirection(KfeDirection.OUTBOUND);
        tx.setDestinationWalletId(UUID.randomUUID());
        when(transactions.findByIdAndUserIdForUpdate(id.value(), 7L)).thenReturn(Optional.of(tx));
        when(transactions.findByIdAndUserId(id.value(), 7L)).thenReturn(Optional.of(tx));
    }

    private PaymentSubmissionCompletionSnapshot snapshot() {
        return new PaymentSubmissionCompletionSnapshot(id, 7L, new IdempotencyKey(" key "),
                ExecutionStatus.EXECUTING, PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND, tx.getDestinationWalletId());
    }

    private KfeTransactionResponse response() {
        var response = mock(KfeTransactionResponse.class);
        when(response.id()).thenReturn(id.value());
        when(response.status()).thenReturn(KfeTransactionStatus.EXECUTING);
        when(response.rail()).thenReturn(KfeRail.LIGHTNING);
        when(response.direction()).thenReturn(KfeDirection.OUTBOUND);
        return response;
    }
}
