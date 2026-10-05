package com.kerosene.kfe.paymentexecution.adapters.in.scheduling;

import com.kerosene.kfe.paymentexecution.application.port.in.ProcessExecutionUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionClaimPort;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class KfeExecutionOutboxWorkerTest {
    private final ExecutionClaimPort claims = mock(ExecutionClaimPort.class);
    private final ProcessExecutionUseCase execution = mock(ProcessExecutionUseCase.class);
    private final KfeExecutionOutboxWorker worker = new KfeExecutionOutboxWorker(claims, execution);

    @Test
    void emptyBatchDoesNotInvokeTheUseCase() {
        when(claims.claimDue(anyString())).thenReturn(List.of());

        worker.drain();

        verify(claims).claimDue(startsWith("kfe-execution-worker-"));
        verifyNoMoreInteractions(claims);
        verifyNoInteractions(execution);
    }

    @Test
    void processesEachClaimUnchangedAndContinuesAfterAnIndividualFailure(CapturedOutput output) {
        var first = claim();
        var second = claim();
        var third = claim();
        String privateDetail = "provider-secret-private-detail";
        when(claims.claimDue(anyString())).thenReturn(List.of(first, second, third));
        doThrow(new IllegalStateException(privateDetail)).when(execution).process(first);

        worker.drain();

        var order = inOrder(claims, execution);
        order.verify(claims).claimDue(anyString());
        order.verify(execution).process(same(first));
        order.verify(execution).process(same(second));
        order.verify(execution).process(same(third));
        order.verifyNoMoreInteractions();
        assertThat(output.getAll()).contains(first.outboxId().toString())
                .doesNotContain(privateDetail, first.claimToken().toString(),
                second.claimToken().toString(), third.claimToken().toString());
    }

    @Test
    void reusesItsWorkerIdentityAcrossBatchesWithoutReusingClaims() {
        when(claims.claimDue(anyString())).thenReturn(List.of());

        worker.drain();
        worker.drain();

        var identity = ArgumentCaptor.forClass(String.class);
        verify(claims, times(2)).claimDue(identity.capture());
        assertThat(identity.getAllValues()).hasSize(2);
        assertThat(identity.getAllValues().get(0)).startsWith("kfe-execution-worker-")
                .isEqualTo(identity.getAllValues().get(1));
        verifyNoInteractions(execution);
    }

    @Test
    void failedClaimAcquisitionPropagatesWithoutInventingAClaimOrCallingTheUseCase() {
        var failure = new IllegalStateException("claim transaction failed");
        when(claims.claimDue(anyString())).thenThrow(failure);

        assertThatThrownBy(worker::drain).isSameAs(failure);

        verify(claims).claimDue(anyString());
        verifyNoMoreInteractions(claims);
        verifyNoInteractions(execution);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusesAnAmbientTransactionBeforeClaimAcquisition(boolean readOnly) throws Exception {
        var connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        var source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setReadOnly(readOnly);

        transaction.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThatThrownBy(worker::drain).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(claims, execution);
        });

        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void doesNotOpenATransactionAroundTheUseCase() {
        var claim = claim();
        when(claims.claimDue(anyString())).thenReturn(List.of(claim));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            return null;
        }).when(execution).process(claim);

        worker.drain();

        verify(execution).process(same(claim));
    }

    private static ExecutionClaim claim() {
        return new ExecutionClaim(UUID.randomUUID(), UUID.randomUUID());
    }
}
