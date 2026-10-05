package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;
import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentApprovalPort;
import com.kerosene.kfe.paymentexecution.application.usecase.AuthorizePaymentService;
import com.kerosene.kfe.paymentexecution.domain.exception.MissingLocalPaymentFactor;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentAuthorizationAdapterTest {
    private final AuthorizePaymentService service = mock(AuthorizePaymentService.class);
    private final PaymentAuthorizationAdapter adapter = new PaymentAuthorizationAdapter(service);

    @Test
    void delegatesTheExactCommandWithoutOpeningATransaction() {
        var command = command(PaymentRail.INTERNAL, PaymentDirection.INTERNAL);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
            return null;
        }).when(service).authorize(command);

        adapter.authorize(command);

        verify(service).authorize(same(command));
        verifyNoMoreInteractions(service);
    }

    @Test
    void missingLocalFactorPreservesTheStructuredUnauthorizedResponseExactly() {
        var command = command(PaymentRail.INTERNAL, PaymentDirection.INTERNAL);
        doThrow(new MissingLocalPaymentFactor()).when(service).authorize(command);

        var error = catchThrowableOfType(() -> adapter.authorize(command), StructuredPlatformException.class);

        assertThat(error.getMessage()).isEqualTo("PIN do aplicativo obrigatorio para transacoes internas KFE e onchain custodial.");
        assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(error.getErrorCode()).isEqualTo(ErrorCodes.AUTH_TRANSACTIONAL_AUTH_REQUIRED);
        assertThat(error.getData()).isInstanceOf(Map.class);
        var data = (Map<?, ?>) error.getData();
        assertThat(data).hasSize(2);
        assertThat((String[]) data.get("requiredAllOf")).containsExactly("appPin", "passkeyAssertionJson");
        assertThat((String[]) data.get("missing")).containsExactly("appPin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"structured", "argument", "state", "unsupported", "error"})
    void unrelatedFailuresPropagateWithoutRemappingOrFallback(String kind) {
        var command = command(PaymentRail.LIGHTNING, PaymentDirection.OUTBOUND);
        Throwable failure = switch (kind) {
            case "structured" -> new StructuredPlatformException("remote denial", HttpStatus.FORBIDDEN, "REMOTE_DENIAL", Map.of("detail", "remote"));
            case "argument" -> new IllegalArgumentException("invalid remote material");
            case "state" -> new IllegalStateException("approval unavailable");
            case "unsupported" -> new UnsupportedOperationException("typed proof required");
            default -> new AssertionError("remote fatal failure");
        };
        doThrow(failure).when(service).authorize(command);

        assertThatThrownBy(() -> adapter.authorize(command)).isSameAs(failure);

        verify(service).authorize(command);
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusesAnyAmbientTransactionBeforeCallingTheServiceEvenWithoutProxy(boolean readOnly) throws Exception {
        var fixture = transaction();
        fixture.template().setReadOnly(readOnly);

        fixture.template().executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThatThrownBy(() -> adapter.authorize(null)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Payment authorization must start outside an existing transaction.");
            verifyNoInteractions(service);
        });

        // The explicit guard does not take ownership of the caller's transaction.
        verify(fixture.connection()).commit();
        verify(fixture.connection(), never()).rollback();
    }

    @Test
    void synchronizationWithoutAnActualTransactionIsNotMistakenForFinancialWork() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            var command = command(PaymentRail.ONCHAIN, PaymentDirection.INBOUND);
            adapter.authorize(command);
            verify(service).authorize(command);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void realServiceMakesOneBoundApprovalAndLeavesCredentialsUnchanged() {
        var approvals = mock(PaymentApprovalPort.class);
        var real = new PaymentAuthorizationAdapter(new AuthorizePaymentService(approvals));
        var command = command(PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND);

        real.authorize(command);

        verify(approvals).approve(same(command));
        verifyNoMoreInteractions(approvals);
    }

    @Test
    void approvalFailureNeverRetriesWithUnboundOrAlternativeFactors() {
        var approvals = mock(PaymentApprovalPort.class);
        var real = new PaymentAuthorizationAdapter(new AuthorizePaymentService(approvals));
        var failure = new UnsupportedOperationException("typed proof required");
        doThrow(failure).when(approvals).approve(any());

        assertThatThrownBy(() -> real.authorize(command(PaymentRail.INTERNAL, PaymentDirection.INTERNAL))).isSameAs(failure);

        verify(approvals).approve(any());
        verifyNoMoreInteractions(approvals);
    }

    private static SubmitPaymentCommand command(PaymentRail rail, PaymentDirection direction) {
        return new SubmitPaymentCommand(7L, new IdempotencyKey(" key "), rail, direction,
                UUID.randomUUID(), UUID.randomUUID(), 10_000L, 100L, " reference ", " memo ",
                " totp ", " assertion ", " passphrase ", " pin ", " public ", 5L, 6, " quote ", " device ");
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
