package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeTransactionResponse;
import com.kerosene.kfe.adapters.out.integration.messaging.KfeRemoteStompRelayClient;
import com.kerosene.kfe.adapters.out.persistence.model.audit.*;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.*;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.*;
import com.kerosene.kfe.adapters.out.persistence.model.messaging.*;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.*;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.*;
import com.kerosene.kfe.adapters.out.persistence.model.shared.*;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.*;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalPaymentSubmissionCompletionAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.messaging.LegacyPaymentSubmissionDashboardAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaIdempotencyReservationStore;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentSubmissionCompletionAdapter;
import com.kerosene.kfe.paymentexecution.application.command.CompletePaymentSubmissionCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.CompletePaymentSubmissionUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.audit.adapters.in.reporting.KfeDashboardService;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeIdempotencyRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Spring graph, legacy publisher and transaction callbacks. SQL rollback is covered separately in PostgreSQL. */
class PaymentSubmissionCompletionWiringTest {
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final KfeIdempotencyRepository reservations = mock(KfeIdempotencyRepository.class);
    private final EntityManager em = mock(EntityManager.class);
    private final PaymentWalletLookupPort wallets = mock(PaymentWalletLookupPort.class);
    private final KfeResponseMapper mapper = mock(KfeResponseMapper.class);
    private final KfeDashboardService dashboard = mock(KfeDashboardService.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final KfeTransactionEntity tx = new KfeTransactionEntity();
    private final KfeIdempotencyEntity reservation = new KfeIdempotencyEntity();
    private final PaymentExecutionId id = new PaymentExecutionId(tx.getId());

    @BeforeEach
    void ready() {
        tx.setUserId(7L);
        tx.setIdempotencyKey("completion-key");
        tx.setStatus(KfeTransactionStatus.EXECUTING);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);
        reservation.setId(new KfeIdempotencyId(7L, "completion-key"));
        reservation.setRequestHash("fingerprint");
        reservation.setStatus("PENDING");
        when(transactions.findByIdAndUserIdForUpdate(id.value(), 7L)).thenReturn(Optional.of(tx));
        when(transactions.findByIdAndUserId(id.value(), 7L)).thenReturn(Optional.of(tx));
        when(transactions.save(tx)).thenReturn(tx);
        when(reservations.findByIdForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(mapper.toTransactionResponse(tx)).thenAnswer(invocation -> {
            var response = mock(KfeTransactionResponse.class);
            when(response.id()).thenReturn(tx.getId());
            when(response.status()).thenReturn(tx.getStatus());
            when(response.rail()).thenReturn(tx.getRail());
            when(response.direction()).thenReturn(tx.getDirection());
            return response;
        });
    }

    @Test
    void graphHasUniquePortsAndNoFinancialCompletionCanRunOutsideTheOwningTransaction() throws Exception {
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(CompletePaymentSubmissionUseCase.class)
                    .hasSingleBean(PaymentSubmissionCompletionPort.class).hasSingleBean(IdempotencyReservationStore.class)
                    .hasSingleBean(PaymentSubmissionDashboardPort.class);
            assertThatThrownBy(() -> ctx.getBean(CompletePaymentSubmissionUseCase.class).complete(command()))
                    .isInstanceOf(IllegalTransactionStateException.class);
            var state = ctx.getBean(PaymentSubmissionCompletionPort.class);
            assertThatThrownBy(() -> state.lockAndLoad(7L, id)).isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> state.saveAndProject(null)).isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(IdempotencyReservationStore.class).complete(7L, null, null))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(PaymentSubmissionDashboardPort.class).publishAfterCommit(7L))
                    .isInstanceOf(IllegalTransactionStateException.class);
            verifyNoInteractions(transactions, reservations, em, wallets, mapper, messaging, dashboard, connection);
        });
    }

    @Test
    void completionSchedulesOneDashboardOnlyAfterCommitAndReplayDoesNotRepublish() throws Exception {
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            var input = ctx.getBean(CompletePaymentSubmissionUseCase.class);
            var transaction = new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class));
            transaction.executeWithoutResult(status -> {
                assertThat(input.complete(command()).id()).isEqualTo(id.value());
                assertThat(reservation.getTransactionId()).isEqualTo(id.value());
                assertThat(reservation.getStatus()).isEqualTo("EXECUTING");
                verifyNoInteractions(messaging, dashboard);
            });
            var order = inOrder(connection, dashboard, messaging);
            order.verify(connection).commit();
            order.verify(dashboard).dashboard(7L);
            order.verify(messaging).convertAndSendToUser("7", KfeDashboardPublisher.DESTINATION, null);
            transaction.executeWithoutResult(status -> input.complete(command()));
            verify(dashboard).dashboard(7L);
            verify(reservations).save(reservation);
        });
    }

    @Test
    void internalCompletionPublishesPayerThenThePersistedDestinationOwner() throws Exception {
        var connection = mock(Connection.class);
        tx.setStatus(KfeTransactionStatus.SETTLED);
        tx.setRail(KfeRail.INTERNAL);
        tx.setDirection(KfeDirection.INTERNAL);
        tx.setDestinationWalletId(UUID.randomUUID());
        when(wallets.findById(tx.getDestinationWalletId())).thenReturn(Optional.of(
                new PaymentWalletSnapshot(tx.getDestinationWalletId(), 8L, true, false, true)));
        context(connection).run(ctx -> {
            new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status -> {
                ctx.getBean(CompletePaymentSubmissionUseCase.class).complete(command());
                verifyNoInteractions(messaging);
            });
            var order = inOrder(messaging);
            order.verify(messaging).convertAndSendToUser("7", KfeDashboardPublisher.DESTINATION, null);
            order.verify(messaging).convertAndSendToUser("8", KfeDashboardPublisher.DESTINATION, null);
            order.verifyNoMoreInteractions();
        });
    }

    @Test
    void projectionFailureCaughtByCallerStillRollsBackAndSuppressesRegisteredCallbacks() throws Exception {
        var connection = mock(Connection.class);
        var failure = new IllegalStateException("projection unavailable");
        when(mapper.toTransactionResponse(tx)).thenThrow(failure);
        context(connection).run(ctx -> {
            assertThatThrownBy(() -> new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status ->
                    assertThatThrownBy(() -> ctx.getBean(CompletePaymentSubmissionUseCase.class).complete(command())).isSameAs(failure)))
                    .isInstanceOf(UnexpectedRollbackException.class);
            verify(connection).rollback();
            verify(connection, never()).commit();
            verifyNoInteractions(messaging, dashboard);
        });
    }

    @Test
    void callerRollbackAfterSuccessfulCompletionSuppressesAllCallbacks() throws Exception {
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status -> {
                ctx.getBean(CompletePaymentSubmissionUseCase.class).complete(command());
                status.setRollbackOnly();
            });
            verify(connection).rollback();
            verifyNoInteractions(messaging, dashboard);
        });
    }

    @Test
    void aTransportFailureAfterCommitCannotRollBackTheCompletedFinancialTransaction() throws Exception {
        var connection = mock(Connection.class);
        var failure = new IllegalStateException("dashboard transport unavailable");
        doThrow(failure).when(messaging).convertAndSendToUser("7", KfeDashboardPublisher.DESTINATION, null);
        context(connection).run(ctx -> {
            assertThatThrownBy(() -> new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status ->
                    ctx.getBean(CompletePaymentSubmissionUseCase.class).complete(command()))).isSameAs(failure);
            verify(connection).commit();
            verify(connection, never()).rollback();
            assertThat(reservation.getTransactionId()).isEqualTo(id.value());
        });
    }

    @Test
    void remoteOnlyTransportRetainsTheSmallDirtyTickPayloadAfterCommit() throws Exception {
        var connection = mock(Connection.class);
        var relay = mock(KfeRemoteStompRelayClient.class);
        // Supply only the transport mock; this test exercises publisher behavior, not relay profile conditions.
        baseContext(connection).withInitializer(ctx -> ctx.getBeanFactory().registerSingleton("completionTestRelay", relay)).run(ctx -> {
            assertThat(ctx).hasSingleBean(KfeRemoteStompRelayClient.class);
            new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status -> {
                ctx.getBean(CompletePaymentSubmissionUseCase.class).complete(command());
                verifyNoInteractions(relay);
            });
            verify(relay).publishToUser(7L, KfeDashboardPublisher.DESTINATION, Map.of("type", "KFE_DASHBOARD_DIRTY", "userId", 7L));
            verifyNoInteractions(dashboard);
        });
    }

    private CompletePaymentSubmissionCommand command() {
        return new CompletePaymentSubmissionCommand(7L, id, new IdempotencyKey("completion-key"), new RequestFingerprint("fingerprint"));
    }

    private ApplicationContextRunner context(Connection connection) throws Exception {
        return baseContext(connection).withBean(SimpMessagingTemplate.class, () -> messaging);
    }

    private ApplicationContextRunner baseContext(Connection connection) throws Exception {
        var source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        return new ApplicationContextRunner().withAllowCircularReferences(false).withUserConfiguration(Graph.class)
                .withBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source))
                .withBean(KfeTransactionRepository.class, () -> transactions).withBean(KfeIdempotencyRepository.class, () -> reservations)
                .withBean(EntityManager.class, () -> em).withBean(PaymentWalletLookupPort.class, () -> wallets)
                .withBean(KfeResponseMapper.class, () -> mapper).withBean(KfeDashboardService.class, () -> dashboard);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({PaymentSubmissionCompletionConfiguration.class, TransactionalPaymentSubmissionCompletionAdapter.class,
            JpaPaymentSubmissionCompletionAdapter.class, JpaIdempotencyReservationStore.class,
            LegacyPaymentSubmissionDashboardAdapter.class, KfeDashboardPublisher.class})
    static class Graph {}
}
