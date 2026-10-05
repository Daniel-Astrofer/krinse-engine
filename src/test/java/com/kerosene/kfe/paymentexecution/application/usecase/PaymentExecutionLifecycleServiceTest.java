package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionAuditPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionRepository;
import com.kerosene.kfe.paymentexecution.domain.exception.InvalidPaymentExecutionTransition;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecution;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentExecutionLifecycleServiceTest {

    private final PaymentExecutionRepository repository = mock(PaymentExecutionRepository.class);
    private final PaymentExecutionAuditPort auditPort = mock(PaymentExecutionAuditPort.class);
    private final PaymentExecutionLifecycleService service =
            new PaymentExecutionLifecycleService(repository, auditPort);
    private final PaymentExecutionId id = new PaymentExecutionId(UUID.randomUUID());

    @Test
    void persistsAcceptedTransitionBeforeRecordingForensicAudit() {
        when(repository.findById(id)).thenReturn(Optional.of(
                PaymentExecution.reconstitute(id, ExecutionStatus.INTENT)));

        var event = service.transition(
                id,
                ExecutionStatus.VALIDATING,
                "KFE_TRANSACTION_VALIDATING",
                Map.of("requestHash", "sha256"));

        ArgumentCaptor<PaymentExecution> saved = ArgumentCaptor.forClass(PaymentExecution.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(ExecutionStatus.VALIDATING);
        assertThat(event.previousStatus()).isEqualTo(ExecutionStatus.INTENT);
        assertThat(event.currentStatus()).isEqualTo(ExecutionStatus.VALIDATING);
        verify(auditPort).record(
                id,
                "KFE_TRANSACTION_VALIDATING",
                ExecutionStatus.INTENT,
                ExecutionStatus.VALIDATING,
                Map.of("requestHash", "sha256"));
    }

    @Test
    void invalidTransitionProducesNeitherPersistenceNorAudit() {
        when(repository.findById(id)).thenReturn(Optional.of(
                PaymentExecution.reconstitute(id, ExecutionStatus.SETTLED)));

        assertThatThrownBy(() -> service.transition(
                id, ExecutionStatus.EXECUTING, "INVALID", Map.of()))
                .isInstanceOf(InvalidPaymentExecutionTransition.class);

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(auditPort, never()).record(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void initialAuditRejectsAStaleExpectedStatus() {
        when(repository.findById(id)).thenReturn(Optional.of(
                PaymentExecution.reconstitute(id, ExecutionStatus.VALIDATING)));

        assertThatThrownBy(() -> service.recordCurrentState(
                id, ExecutionStatus.INTENT, "KFE_TRANSACTION_INTENT", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected INTENT")
                .hasMessageContaining("VALIDATING");

        verify(auditPort, never()).record(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
