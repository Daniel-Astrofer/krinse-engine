package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.PaymentAuthorizationAdapter;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.PaymentPreflightAdapter;
import com.kerosene.kfe.paymentexecution.application.command.ResolvePaymentWalletsCommand;
import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.AuthorizePaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.GetIdempotentPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentWalletsUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.PreflightPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.ReservePaymentIdempotencyUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentApprovalPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCanonicalDestinationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentDestinationValidationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestFingerprintPort;
import com.kerosene.kfe.paymentexecution.application.query.GetIdempotentPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.result.CanonicalPaymentDestination;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Spring graph and transaction boundaries, with remote approval and routing replaced by ports. */
class PaymentPreflightWiringTest {
    private final PaymentWalletsUseCase wallets = mock(PaymentWalletsUseCase.class);
    private final PaymentCanonicalDestinationPort destinations = mock(PaymentCanonicalDestinationPort.class);
    private final PaymentDestinationValidationPort validation = mock(PaymentDestinationValidationPort.class);
    private final PaymentRequestFingerprintPort fingerprints = mock(PaymentRequestFingerprintPort.class);
    private final GetIdempotentPaymentUseCase replay = mock(GetIdempotentPaymentUseCase.class);
    private final PaymentApprovalPort approvals = mock(PaymentApprovalPort.class);
    private final RequestFingerprint fingerprint = new RequestFingerprint(" raw-fingerprint ");

    @Test
    void graphHasExactlyOneInputOfEachKindAndDoesNotIncludeFinancialReservation() throws Exception {
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(PreflightPaymentUseCase.class)
                    .hasSingleBean(AuthorizePaymentUseCase.class).hasSingleBean(PaymentPreflightAdapter.class)
                    .hasSingleBean(PaymentAuthorizationAdapter.class).doesNotHaveBean(ReservePaymentIdempotencyUseCase.class);
            assertThat(ctx.getBean(PreflightPaymentUseCase.class)).isSameAs(ctx.getBean(PaymentPreflightAdapter.class));
            assertThat(ctx.getBean(AuthorizePaymentUseCase.class)).isSameAs(ctx.getBean(PaymentAuthorizationAdapter.class));
            verifyNoInteractions(connection, wallets, destinations, validation, fingerprints, replay, approvals);
        });
    }

    @Test
    void canonicalizationValidationFingerprintReplayAndApprovalsRetainTheirOrderOutsideATransaction() throws Exception {
        var connection = mock(Connection.class);
        var original = command(" pin ");
        var destinationWallet = UUID.randomUUID();
        var resolved = original.withDestinationWalletId(destinationWallet);
        var canonical = resolved.withCanonicalDestination(" canonical-address ", " canonical memo ");
        when(wallets.resolveDestinationReference(walletCommand(original))).thenReturn(destinationWallet);
        when(destinations.resolve(resolved)).thenReturn(new CanonicalPaymentDestination(" canonical-address ", " canonical memo "));
        when(fingerprints.fingerprint(canonical)).thenReturn(fingerprint);
        doAnswer(invocation -> {
            assertNoAmbientTransaction();
            return null;
        }).when(approvals).approve(canonical);

        context(connection).run(ctx -> {
            var result = ctx.getBean(PreflightPaymentUseCase.class).preflight(original);
            assertThat(result.command()).isEqualTo(canonical);
            assertThat(result.fingerprint()).isEqualTo(fingerprint);
            assertThat(result.existingPayment()).isEmpty();
            var order = inOrder(wallets, destinations, validation, fingerprints, replay, approvals);
            order.verify(wallets).resolveDestinationReference(walletCommand(original));
            order.verify(destinations).resolve(resolved);
            order.verify(validation).validate(PaymentRail.ONCHAIN, " canonical-address ");
            order.verify(fingerprints).fingerprint(canonical);
            order.verify(replay).find(new GetIdempotentPaymentQuery(7L, canonical.idempotencyKey(), fingerprint));
            order.verify(wallets).requireNotSelfPayment(walletCommand(canonical));
            order.verify(approvals).approve(canonical);
            order.verifyNoMoreInteractions();
            verifyNoInteractions(connection);
        });
    }

    @Test
    void completedReplaySkipsSelfPaymentAndMissingFactorChecksWithoutOpeningAFinancialTransaction() throws Exception {
        var connection = mock(Connection.class);
        var command = command(null);
        prepare(command);
        var existing = mock(PaymentExecutionResult.class);
        when(replay.find(any())).thenReturn(Optional.of(existing));
        context(connection).run(ctx -> {
            var result = ctx.getBean(PreflightPaymentUseCase.class).preflight(command);
            assertThat(result.existingPayment()).containsSame(existing);
            verify(wallets, never()).requireNotSelfPayment(any());
            verifyNoInteractions(approvals, connection);
        });
    }

    @Test
    void missingLocalFactorRetainsTheStructuredUnauthorizedErrorWithoutCallingRemoteApproval() throws Exception {
        var connection = mock(Connection.class);
        var command = command("   ");
        prepare(command);
        context(connection).run(ctx -> {
            var thrown = catchThrowableOfType(() -> ctx.getBean(PreflightPaymentUseCase.class).preflight(command),
                    StructuredPlatformException.class);
            assertThat(thrown).isNotNull();
            assertThat(thrown.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(thrown.getErrorCode()).isEqualTo(ErrorCodes.AUTH_TRANSACTIONAL_AUTH_REQUIRED);
            verifyNoInteractions(approvals, connection);
        });
    }

    @Test
    void rejectedApprovalCannotRetryOrStartFinancialWork() throws Exception {
        var connection = mock(Connection.class);
        var command = command(" pin ");
        prepare(command);
        var failure = new IllegalStateException("local approval denied");
        doThrow(failure).when(approvals).approve(command);
        context(connection).run(ctx -> {
            assertThatThrownBy(() -> ctx.getBean(PreflightPaymentUseCase.class).preflight(command)).isSameAs(failure);
            verify(approvals).approve(command);
            verifyNoMoreInteractions(approvals);
            verifyNoInteractions(connection);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ambientTransactionIsRejectedBeforeAnyPortForBothInputs(boolean authorizationOnly) throws Exception {
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThatThrownBy(() -> new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class))
                    .executeWithoutResult(status -> {
                        if (authorizationOnly) { ctx.getBean(AuthorizePaymentUseCase.class).authorize(command(" pin ")); }
                        else { ctx.getBean(PreflightPaymentUseCase.class).preflight(command(" pin ")); }
                    })).isInstanceOf(IllegalStateException.class).hasMessageContaining("outside an existing transaction");
            assertThatCode(() -> verify(connection).rollback()).doesNotThrowAnyException();
            assertThatCode(() -> verify(connection, never()).commit()).doesNotThrowAnyException();
            verifyNoInteractions(wallets, destinations, validation, fingerprints, replay, approvals);
        });
    }

    @Test
    void shortReplayReadTransactionFinishesBeforeRemoteAuthorizationBegins() throws Exception {
        var connection = mock(Connection.class);
        var command = command(" pin ");
        prepare(command);
        context(connection).run(ctx -> {
            when(replay.find(any())).thenAnswer(invocation -> {
                var read = new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class));
                read.setReadOnly(true);
                return read.execute(status -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
                    return Optional.empty();
                });
            });
            doAnswer(invocation -> {
                assertNoAmbientTransaction();
                verify(connection).commit();
                verify(connection).close();
                return null;
            }).when(approvals).approve(command);

            assertThat(ctx.getBean(PreflightPaymentUseCase.class).preflight(command).existingPayment()).isEmpty();

            var order = inOrder(connection, approvals);
            assertThatCode(() -> order.verify(connection).commit()).doesNotThrowAnyException();
            assertThatCode(() -> order.verify(connection).close()).doesNotThrowAnyException();
            order.verify(approvals).approve(command);
            assertThatCode(() -> verify(connection, never()).rollback()).doesNotThrowAnyException();
        });
    }

    private void prepare(SubmitPaymentCommand command) {
        when(wallets.resolveDestinationReference(walletCommand(command))).thenReturn(command.destinationWalletId());
        when(destinations.resolve(command)).thenReturn(new CanonicalPaymentDestination(command.externalReference(), command.memo()));
        when(fingerprints.fingerprint(command)).thenReturn(fingerprint);
    }

    private static SubmitPaymentCommand command(String appPin) {
        return new SubmitPaymentCommand(7L, new IdempotencyKey(" key "), PaymentRail.ONCHAIN,
                PaymentDirection.OUTBOUND, UUID.randomUUID(), null, 10_000L, 100L, " address ", " memo ",
                " totp ", " assertion ", " passphrase ", appPin, " request ", 12L, 3, " quote ", " device ");
    }

    private static ResolvePaymentWalletsCommand walletCommand(SubmitPaymentCommand command) {
        return new ResolvePaymentWalletsCommand(command.userId(), command.rail(), command.direction(),
                command.sourceWalletId(), command.destinationWalletId(), command.externalReference());
    }

    private static void assertNoAmbientTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    private ApplicationContextRunner context(Connection connection) throws Exception {
        var source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        return new ApplicationContextRunner().withAllowCircularReferences(false).withUserConfiguration(Graph.class)
                .withBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source))
                .withBean(PaymentWalletsUseCase.class, () -> wallets)
                .withBean(PaymentCanonicalDestinationPort.class, () -> destinations)
                .withBean(PaymentDestinationValidationPort.class, () -> validation)
                .withBean(PaymentRequestFingerprintPort.class, () -> fingerprints)
                .withBean(GetIdempotentPaymentUseCase.class, () -> replay)
                .withBean(PaymentApprovalPort.class, () -> approvals);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({PaymentPreflightConfiguration.class, PaymentPreflightAdapter.class, PaymentAuthorizationAdapter.class})
    static class Graph {}
}
