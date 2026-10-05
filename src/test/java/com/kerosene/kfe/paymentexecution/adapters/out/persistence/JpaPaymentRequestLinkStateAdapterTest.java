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
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.repository.Lock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JpaPaymentRequestLinkStateAdapterTest {
    private final KfePaymentRequestRepository requests = mock(KfePaymentRequestRepository.class);
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final EntityManager em = mock(EntityManager.class);
    private final JpaPaymentRequestLinkStateAdapter adapter = new JpaPaymentRequestLinkStateAdapter(requests, transactions, em);

    @Test
    void publicRequestLockRefreshesPreviouslyManagedStateWithoutPayerOwnershipFilter() throws Exception {
        var request = request();
        when(requests.findByPublicIdForUpdate("public-id")).thenReturn(Optional.of(request));
        doAnswer(invocation -> { request.setStatus(KfePaymentRequestStatus.PAID); return null; })
                .when(em).refresh(request, LockModeType.PESSIMISTIC_WRITE);
        var snapshot = adapter.lockByPublicId("public-id");
        assertThat(snapshot.recipientUserId()).isEqualTo(8L);
        assertThat(snapshot.open()).isFalse();
        assertThat(snapshot.expiresAt()).isEqualTo(request.getExpiresAt().toInstant(ZoneOffset.UTC));
        var order = inOrder(requests, em);
        order.verify(requests).findByPublicIdForUpdate("public-id");
        order.verify(em).refresh(request, LockModeType.PESSIMISTIC_WRITE);
        order.verifyNoMoreInteractions();
        assertThat(KfePaymentRequestRepository.class.getMethod("findByPublicIdForUpdate", String.class)
                .getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"public-id", "owner", "id"})
    void refreshedMismatchedRequestIdentityCannotBeReturned(String mismatch) {
        var request = request();
        UUID requested = mismatch.equals("id") ? UUID.randomUUID() : request.getId();
        when(requests.findByPublicIdForUpdate("public-id")).thenReturn(Optional.of(request));
        when(requests.findByIdAndUserIdForUpdate(requested, 8L)).thenReturn(Optional.of(request));
        doAnswer(invocation -> {
            if (mismatch.equals("public-id")) { request.setPublicId("other"); }
            if (mismatch.equals("owner")) { request.setUserId(9L); }
            return null;
        }).when(em).refresh(request, LockModeType.PESSIMISTIC_WRITE);
        assertThatThrownBy(() -> {
            if (mismatch.equals("public-id")) { adapter.lockByPublicId("public-id"); }
            else { adapter.lockById(8L, requested); }
        }).isInstanceOf(IllegalArgumentException.class).hasMessage("KFE payment request not found.");
        verifyNoInteractions(transactions);
    }

    @Test
    void mapsRefreshedPayerExecutionRatherThanRecipientOrCallerAmounts() {
        var tx = transaction();
        var id = new PaymentExecutionId(tx.getId());
        when(transactions.findByIdAndUserIdForUpdate(tx.getId(), 7L)).thenReturn(Optional.of(tx));
        doAnswer(invocation -> { tx.setStatus(KfeTransactionStatus.SETTLED); tx.setGrossAmountSats(12_345L); return null; })
                .when(em).refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        var result = adapter.lockExecution(7L, id);
        assertThat(result.userId()).isEqualTo(7L);
        assertThat(result.status()).isEqualTo(ExecutionStatus.SETTLED);
        assertThat(result.grossAmountSats()).isEqualTo(12_345L);
        assertThat(result.externalReference()).isEqualTo("public-id");
        var order = inOrder(transactions, em);
        order.verify(transactions).findByIdAndUserIdForUpdate(tx.getId(), 7L);
        order.verify(em).refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "id", "missing"})
    void rejectsForeignOrWrongExecution(String mismatch) {
        var tx = transaction();
        var requestedId = new PaymentExecutionId(mismatch.equals("id") ? UUID.randomUUID() : tx.getId());
        if (!mismatch.equals("missing")) { when(transactions.findByIdAndUserIdForUpdate(requestedId.value(), 7L)).thenReturn(Optional.of(tx)); }
        doAnswer(invocation -> { if (mismatch.equals("owner")) { tx.setUserId(8L); } return null; })
                .when(em).refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        assertThatThrownBy(() -> adapter.lockExecution(7L, requestedId))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("KFE transaction not found.");
        verifyNoInteractions(requests);
    }

    @Test
    void writesPaidOnlyWhenCurrentScopedSnapshotStillMatches() {
        var request = request();
        when(requests.findByIdAndUserIdForUpdate(request.getId(), 8L)).thenReturn(Optional.of(request));
        var previous = adapter.lockById(8L, request.getId());
        when(requests.findByIdAndUserId(request.getId(), 8L)).thenReturn(Optional.of(request));
        var id = new PaymentExecutionId(UUID.randomUUID());
        adapter.markPaid(previous, id);
        assertThat(request.getStatus()).isEqualTo(KfePaymentRequestStatus.PAID);
        assertThat(request.getPaidTransactionId()).isEqualTo(id.value());
        verify(requests).save(request);
        // Replaying the same accepted link is an idempotent no-op; it must not write again.
        assertThatCode(() -> adapter.markPaid(previous, id)).doesNotThrowAnyException();
        verify(requests, times(1)).save(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "amount", "wallet", "owner", "paid-link", "expiry"})
    void changedStateCannotBeOverwrittenByAnAcceptedSnapshot(String field) {
        var request = request();
        when(requests.findByIdAndUserIdForUpdate(request.getId(), 8L)).thenReturn(Optional.of(request));
        var previous = adapter.lockById(8L, request.getId());
        when(requests.findByIdAndUserId(request.getId(), 8L)).thenReturn(Optional.of(request));
        switch (field) {
            case "status" -> request.setStatus(KfePaymentRequestStatus.CANCELLED);
            case "amount" -> request.setAmountSats(9999L);
            case "wallet" -> request.setWalletId(UUID.randomUUID());
            case "owner" -> request.setUserId(9L);
            case "paid-link" -> request.setPaidTransactionId(UUID.randomUUID());
            case "expiry" -> request.setExpiresAt(request.getExpiresAt().plusSeconds(1));
        }
        assertThatThrownBy(() -> adapter.markPaid(previous, new PaymentExecutionId(UUID.randomUUID()))).isInstanceOf(RuntimeException.class);
        verify(requests, never()).save(any());
    }

    @Test
    void missingSelectorsFailBeforePersistence() {
        assertThatThrownBy(() -> adapter.lockByPublicId(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.lockById(0L, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.lockExecution(7L, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(requests, transactions, em);
    }

    private KfePaymentRequestEntity request() {
        var request = new KfePaymentRequestEntity();
        request.setPublicId("public-id"); request.setUserId(8L); request.setWalletId(UUID.randomUUID());
        request.setRail(KfeRail.INTERNAL); request.setAmountSats(10_000L);
        request.setExpiresAt(LocalDateTime.of(2026, 9, 13, 12, 0));
        return request;
    }
    private KfeTransactionEntity transaction() {
        var tx = new KfeTransactionEntity();
        tx.setUserId(7L); tx.setRail(KfeRail.INTERNAL); tx.setDirection(KfeDirection.INTERNAL);
        tx.setDestinationWalletId(UUID.randomUUID()); tx.setExternalReference("public-id");
        return tx;
    }
}
