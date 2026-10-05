package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.adapters.out.persistence.model.audit.*;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.*;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.*;
import com.kerosene.kfe.adapters.out.persistence.model.messaging.*;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.*;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.*;
import com.kerosene.kfe.adapters.out.persistence.model.shared.*;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.*;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalPaymentRequestLinkAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentRequestLinkStateAdapter;
import com.kerosene.kfe.paymentexecution.application.command.*;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentRequestLinkUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestLinkStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentWalletLookupPort;
import com.kerosene.kfe.paymentexecution.application.result.PreparedPaymentRequestLink;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentRequestLinkWiringTest {
    private final KfePaymentRequestRepository requests = mock(KfePaymentRequestRepository.class);
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final PaymentWalletLookupPort wallets = mock(PaymentWalletLookupPort.class);
    private final EntityManager em = mock(EntityManager.class);
    private final KfePaymentRequestEntity request = new KfePaymentRequestEntity();
    private final KfeTransactionEntity execution = new KfeTransactionEntity();
    private final UUID destination = UUID.randomUUID();
    private final PaymentExecutionId executionId = new PaymentExecutionId(execution.getId());

    @Test
    void graphHasUniquePortsAndRejectsPrepareCompleteAndStateWithoutOwningTransaction() throws Exception {
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(PaymentRequestLinkUseCase.class).hasSingleBean(PaymentRequestLinkStatePort.class);
            var useCase = ctx.getBean(PaymentRequestLinkUseCase.class);
            assertThatThrownBy(() -> useCase.prepare(command())).isInstanceOf(IllegalTransactionStateException.class);
            var accepted = new PreparedPaymentRequestLink(7L, new PaymentRequestLinkSnapshot(request.getId(), "public-id",
                    8L, destination, PaymentRail.INTERNAL, true, null, null, null), 10_000L);
            assertThatThrownBy(() -> useCase.complete(new CompletePaymentRequestLinkCommand(7L, accepted, executionId)))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(PaymentRequestLinkStatePort.class).lockByPublicId("public-id"))
                    .isInstanceOf(IllegalTransactionStateException.class);
            verifyNoInteractions(requests, transactions, wallets, em, connection);
        });
    }

    @Test
    void acceptanceAndPaidLinkJoinOneCallerCommitWithoutRequiringPayerToOwnTheRequest() throws Exception {
        ready();
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            var useCase = ctx.getBean(PaymentRequestLinkUseCase.class);
            new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status -> {
                var accepted = useCase.prepare(command()).orElseThrow();
                assertThat(accepted.request().recipientUserId()).isEqualTo(8L);
                useCase.complete(new CompletePaymentRequestLinkCommand(7L, accepted, executionId));
                assertThat(request.getStatus()).isEqualTo(KfePaymentRequestStatus.PAID);
                assertThat(request.getPaidTransactionId()).isEqualTo(executionId.value());
                assertThatCode(() -> verify(connection, never()).commit()).doesNotThrowAnyException();
            });
            var order = inOrder(requests, transactions, connection);
            order.verify(requests).findByPublicIdForUpdate("public-id");
            order.verify(requests).findByIdAndUserIdForUpdate(request.getId(), 8L);
            order.verify(transactions).findByIdAndUserIdForUpdate(executionId.value(), 7L);
            order.verify(requests).findByIdAndUserId(request.getId(), 8L);
            order.verify(requests).save(request);
            order.verify(connection).commit();
            verify(connection, never()).rollback();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"unsettled", "save"})
    void caughtCompletionFailureStillRequiresRollback(String stage) throws Exception {
        ready();
        if (stage.equals("unsettled")) { execution.setStatus(KfeTransactionStatus.LOCKED); }
        else { when(requests.save(request)).thenThrow(new IllegalStateException("request write failed")); }
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            var useCase = ctx.getBean(PaymentRequestLinkUseCase.class);
            assertThatThrownBy(() -> new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class))
                    .executeWithoutResult(status -> {
                        var accepted = useCase.prepare(command()).orElseThrow();
                        assertThatThrownBy(() -> useCase.complete(new CompletePaymentRequestLinkCommand(7L, accepted, executionId)))
                                .isInstanceOf(IllegalStateException.class);
                    })).isInstanceOf(UnexpectedRollbackException.class);
            verify(connection).rollback();
            verify(connection, never()).commit();
        });
    }

    @Test
    void outerRollbackIncludesSuccessfulPaidLinkWithoutIndependentCommit() throws Exception {
        ready();
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            var useCase = ctx.getBean(PaymentRequestLinkUseCase.class);
            new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status -> {
                var accepted = useCase.prepare(command()).orElseThrow();
                useCase.complete(new CompletePaymentRequestLinkCommand(7L, accepted, executionId));
                status.setRollbackOnly();
            });
            verify(connection).rollback();
            verify(connection, never()).commit();
        });
    }

    private void ready() {
        request.setUserId(8L); request.setPublicId("public-id"); request.setWalletId(destination);
        request.setRail(KfeRail.LIGHTNING); request.setAmountSats(10_000L);
        execution.setUserId(7L); execution.setRail(KfeRail.INTERNAL); execution.setDirection(KfeDirection.INTERNAL);
        execution.setStatus(KfeTransactionStatus.SETTLED); execution.setDestinationWalletId(destination);
        execution.setGrossAmountSats(10_000L); execution.setReceiverAmountSats(9_910L); execution.setExternalReference("public-id");
        when(requests.findByPublicIdForUpdate("public-id")).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return Optional.of(request);
        });
        when(requests.findByIdAndUserIdForUpdate(request.getId(), 8L)).thenReturn(Optional.of(request));
        when(requests.findByIdAndUserId(request.getId(), 8L)).thenReturn(Optional.of(request));
        when(transactions.findByIdAndUserIdForUpdate(executionId.value(), 7L)).thenReturn(Optional.of(execution));
        when(wallets.findOwnedDestination(8L, destination)).thenReturn(Optional.of(new PaymentWalletSnapshot(destination, 8L, true, false, true)));
    }
    private PreparePaymentRequestLinkCommand command() { return new PreparePaymentRequestLinkCommand(7L, PaymentRail.INTERNAL, PaymentDirection.INTERNAL, destination, 10_000L, " public-id "); }
    private ApplicationContextRunner context(Connection connection) throws Exception {
        var ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        return new ApplicationContextRunner().withAllowCircularReferences(false).withUserConfiguration(Graph.class)
                .withBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(ds))
                .withBean(KfePaymentRequestRepository.class, () -> requests)
                .withBean(KfeTransactionRepository.class, () -> transactions)
                .withBean(PaymentWalletLookupPort.class, () -> wallets)
                .withBean(EntityManager.class, () -> em);
    }
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({PaymentRequestLinkConfiguration.class, TransactionalPaymentRequestLinkAdapter.class, JpaPaymentRequestLinkStateAdapter.class})
    static class Graph {}
}
