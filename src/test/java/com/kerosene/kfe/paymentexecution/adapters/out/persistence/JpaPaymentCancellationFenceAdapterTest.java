package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeExecutionOutboxEntity;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentCancellationRejected;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JpaPaymentCancellationFenceAdapterTest {
    private final KfeExecutionOutboxRepository outboxes = mock(KfeExecutionOutboxRepository.class);
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final JpaPaymentCancellationFenceAdapter adapter =
            new JpaPaymentCancellationFenceAdapter(outboxes, transactions, entityManager);
    private final KfeTransactionEntity tx = transaction();
    private final PaymentExecutionId id = new PaymentExecutionId(tx.getId());

    @Test
    void cancelsPendingCommandUnderOutboxThenTransactionLocks() {
        var command = command("PENDING");
        stub(command);
        adapter.fence(List.of(id));
        var order = inOrder(outboxes, transactions, entityManager);
        order.verify(outboxes).findByTransactionIdInForUpdate(List.of(id.value()));
        order.verify(entityManager).refresh(command, LockModeType.PESSIMISTIC_WRITE);
        order.verify(transactions).findByIdForUpdate(id.value());
        order.verify(entityManager).refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        order.verify(outboxes).save(command);
        assertThat(command.getStatus()).isEqualTo("FAILED_FINAL");
        assertThat(command.getLastError()).isEqualTo("USER_CANCELLED");
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROCESSING", "UNKNOWN", "FAILED_RETRYABLE", "DISPATCHED", "FAILED_FINAL"})
    void refusesAnyCommandThatMayHaveExecuted(String status) {
        stub(command(status));
        assertThatThrownBy(() -> adapter.fence(List.of(id))).isInstanceOf(PaymentCancellationRejected.class);
        verify(outboxes, never()).save(any());
    }

    @Test
    void pendingWithPreparedPayloadIsNotEvidenceOfNonExecution() {
        var command = command("PENDING");
        command.setPreparedPayloadHash("hash");
        stub(command);
        assertThatThrownBy(() -> adapter.fence(List.of(id))).isInstanceOf(PaymentCancellationRejected.class);
    }

    @Test
    void refreshPreventsCancellationUsingStalePreBroadcastSnapshot() {
        var command = command("PENDING");
        stub(command);
        doAnswer(invocation -> {
            tx.setBlockchainTxid("observed-after-initial-query");
            return null;
        }).when(entityManager).refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        assertThatThrownBy(() -> adapter.fence(List.of(id))).isInstanceOf(PaymentCancellationRejected.class);
        verify(outboxes, never()).save(any());
    }

    @ParameterizedTest
    @MethodSource("dispatchEvidence")
    void pendingStatusAloneCannotHidePriorExecution(Consumer<KfeExecutionOutboxEntity> evidence) {
        var command = command("PENDING");
        evidence.accept(command);
        stub(command);

        assertThatThrownBy(() -> adapter.fence(List.of(id))).isInstanceOf(PaymentCancellationRejected.class);
        verify(outboxes, never()).save(any());
    }

    private static Stream<Consumer<KfeExecutionOutboxEntity>> dispatchEvidence() {
        return Stream.of(
                command -> command.setAttempts(1),
                command -> command.setClaimToken(UUID.randomUUID()),
                command -> command.setClaimedAt(LocalDateTime.now()),
                command -> command.setClaimedBy("worker-1"),
                command -> command.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1)),
                command -> command.setDispatchedAt(LocalDateTime.now()),
                command -> command.setProviderReference("provider-ref"),
                command -> command.setPreparedPayloadCiphertext("ciphertext"),
                command -> command.setPreparedPayloadHash("hash"),
                command -> command.setExecutionReference("execution-ref"));
    }

    @Test
    void allExecutionsAreValidatedBeforeAnyCommandIsClosed() {
        var unsafeTx = transaction();
        var unsafeId = new PaymentExecutionId(unsafeTx.getId());
        var pending = command("PENDING");
        var claimed = command("PROCESSING");
        claimed.setTransactionId(unsafeId.value());
        when(outboxes.findByTransactionIdInForUpdate(any())).thenReturn(List.of(pending, claimed));
        when(transactions.findByIdForUpdate(any())).thenAnswer(invocation -> {
            UUID value = invocation.getArgument(0);
            return Optional.of(value.equals(id.value()) ? tx : unsafeTx);
        });

        assertThatThrownBy(() -> adapter.fence(List.of(id, unsafeId)))
                .isInstanceOf(PaymentCancellationRejected.class);
        assertThat(pending.getStatus()).isEqualTo("PENDING");
        verify(outboxes, never()).save(any());
    }

    @Test
    void fenceCannotOpenAnIndependentFinancialTransaction() {
        assertThat(JpaPaymentCancellationFenceAdapter.class.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.MANDATORY);
    }

    private void stub(KfeExecutionOutboxEntity command) {
        when(outboxes.findByTransactionIdInForUpdate(List.of(id.value()))).thenReturn(List.of(command));
        when(transactions.findByIdForUpdate(id.value())).thenReturn(Optional.of(tx));
    }

    private KfeExecutionOutboxEntity command(String status) {
        var command = new KfeExecutionOutboxEntity();
        command.setTransactionId(id.value());
        command.setStatus(status);
        return command;
    }

    private KfeTransactionEntity transaction() {
        var entity = new KfeTransactionEntity();
        entity.setStatus(KfeTransactionStatus.EXECUTING);
        entity.setRail(KfeRail.ONCHAIN);
        entity.setDirection(KfeDirection.OUTBOUND);
        return entity;
    }
}
