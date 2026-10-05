package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.usecase.ProcessExecutionService;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ProcessExecutionAdapterTest {
    private final ProcessExecutionService service = mock(ProcessExecutionService.class);
    private final ProcessExecutionAdapter adapter = new ProcessExecutionAdapter(service);
    private final ExecutionClaim claim = new ExecutionClaim(UUID.randomUUID(), UUID.randomUUID());

    @Test
    void forwardsTheSameClaimWithoutOpeningATransactionOrSynchronization() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            return null;
        }).when(service).process(claim);

        adapter.process(claim);

        verify(service).process(same(claim));
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAnAmbientTransactionBeforeDelegatingEvenWithoutASpringProxy(boolean readOnly) throws Exception {
        var connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        var source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setReadOnly(readOnly);

        transaction.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThatThrownBy(() -> adapter.process(claim)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> adapter.process(null)).isInstanceOf(IllegalStateException.class);
            verifyNoInteractions(service);
        });

        // A rejected entry must not acquire ownership of the caller's transaction.
        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void synchronizationAloneDoesNotPreventProcessing() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            adapter.process(claim);
            verify(service).process(same(claim));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"argument", "state", "error"})
    void propagatesServiceFailuresWithoutRetryOrReclassification(String kind) {
        Throwable failure = switch (kind) {
            case "argument" -> new IllegalArgumentException("invalid preparation");
            case "state" -> new IllegalStateException("outcome persistence unavailable");
            default -> new AssertionError("fatal execution failure");
        };
        doThrow(failure).when(service).process(claim);

        assertThatThrownBy(() -> adapter.process(claim)).isSameAs(failure);

        verify(service).process(same(claim));
        verifyNoMoreInteractions(service);
    }
}
