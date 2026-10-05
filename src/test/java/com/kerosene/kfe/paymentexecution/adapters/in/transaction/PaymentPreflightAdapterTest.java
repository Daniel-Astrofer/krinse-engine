package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.application.result.PaymentPreflightResult;
import com.kerosene.kfe.paymentexecution.application.usecase.PreflightPaymentService;
import com.kerosene.kfe.paymentexecution.domain.exception.MissingLocalPaymentFactor;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentPreflightAdapterTest {
    private final PreflightPaymentService service = mock(PreflightPaymentService.class);
    private final PaymentPreflightAdapter adapter = new PaymentPreflightAdapter(service);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void delegatesOutsideATransactionAndPreservesBothNewAndReplayResults(boolean replay) {
        var command = command();
        var canonical = command.withCanonicalDestination(" canonical ", " canonical memo ");
        var result = new PaymentPreflightResult(canonical, new RequestFingerprint("fingerprint"),
                replay ? Optional.of(mock(PaymentExecutionResult.class)) : Optional.empty());
        when(service.preflight(command)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            return result;
        });

        assertThat(adapter.preflight(command)).isSameAs(result);

        verify(service).preflight(same(command));
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAmbientReadOnlyOrReadWriteTransactionBeforeServiceEvenWithoutAProxy(boolean readOnly) throws Exception {
        var fixture = transaction();
        fixture.template().setReadOnly(readOnly);

        fixture.template().executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThatThrownBy(() -> adapter.preflight(null)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Payment preflight must start outside an existing transaction.");
            verifyNoInteractions(service);
        });

        verify(fixture.connection()).commit();
        verify(fixture.connection(), never()).rollback();
    }

    @Test
    void uncaughtGuardFailureLeavesRollbackToTheTransactionOwner() throws Exception {
        var fixture = transaction();

        assertThatThrownBy(() -> fixture.template().executeWithoutResult(status -> adapter.preflight(command())))
                .isInstanceOf(IllegalStateException.class);

        verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
        verifyNoInteractions(service);
    }

    @Test
    void synchronizationAloneDoesNotCreateAnAmbientTransaction() {
        var command = command();
        var result = new PaymentPreflightResult(command, new RequestFingerprint("fingerprint"), Optional.empty());
        when(service.preflight(command)).thenReturn(result);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(adapter.preflight(command)).isSameAs(result);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"argument", "state", "domain", "error"})
    void failuresAreNotConvertedToSuccessOrRetried(String kind) {
        var command = command();
        Throwable failure = switch (kind) {
            case "argument" -> new IllegalArgumentException("invalid destination");
            case "state" -> new IllegalStateException("idempotency conflict");
            case "domain" -> new MissingLocalPaymentFactor();
            default -> new AssertionError("fatal failure");
        };
        when(service.preflight(command)).thenThrow(failure);

        assertThatThrownBy(() -> adapter.preflight(command)).isSameAs(failure);

        verify(service).preflight(command);
        verifyNoMoreInteractions(service);
    }

    private static SubmitPaymentCommand command() {
        return new SubmitPaymentCommand(7L, new IdempotencyKey("key"), PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND,
                UUID.randomUUID(), null, 10_000L, 100L, "reference", "memo", "totp", "assertion",
                "passphrase", "pin", "public", 5L, 6, "quote", "device");
    }

    private static Fixture transaction() throws Exception {
        var connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        var source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        return new Fixture(new TransactionTemplate(new DataSourceTransactionManager(source)), connection);
    }

    private record Fixture(TransactionTemplate template, Connection connection) {}
}
