package com.kerosene.kfe.paymentexecution.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.adapters.out.persistence.model.audit.*;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.*;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.*;
import com.kerosene.kfe.adapters.out.persistence.model.messaging.*;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.*;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.*;
import com.kerosene.kfe.adapters.out.persistence.model.shared.*;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.*;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalPaymentRoutingAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentRoutingStateAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaExecutionCommandStoreAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.messaging.LegacyPaymentInitiatedNotificationAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.remote.LegacyPaymentVaultIntentAdapter;
import com.kerosene.kfe.paymentexecution.application.command.*;
import com.kerosene.kfe.paymentexecution.application.port.in.*;
import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.paymentexecution.adapters.out.vault.KfeVaultMeshIntentService;
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
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentRoutingWiringTest {
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final KfeExecutionOutboxRepository outboxes = mock(KfeExecutionOutboxRepository.class);
    private final PaymentStatementPort statements = mock(PaymentStatementPort.class);
    private final FinancialNotificationPort notifications = mock(FinancialNotificationPort.class);
    private final KfeVaultMeshIntentService vault = mock(KfeVaultMeshIntentService.class);
    private final SettleInternalPaymentUseCase internal = mock(SettleInternalPaymentUseCase.class);
    private final PaymentExecutionLifecycleUseCase lifecycle = mock(PaymentExecutionLifecycleUseCase.class);
    private final EntityManager em = mock(EntityManager.class);
    private final KfeTransactionEntity tx = new KfeTransactionEntity();
    private final PaymentExecutionId id = new PaymentExecutionId(tx.getId());

    @Test
    void graphHasUniquePortsAndRoutingStateFlushAndOutboxRequireTheOwningTransaction() throws Exception {
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(RouteLockedPaymentUseCase.class).hasSingleBean(PaymentRoutingStatePort.class)
                    .hasSingleBean(ExecutionCommandStore.class).hasSingleBean(PaymentInitiatedNotificationPort.class).hasSingleBean(PaymentVaultIntentPort.class);
            assertThatThrownBy(() -> ctx.getBean(RouteLockedPaymentUseCase.class).route(command())).isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(PaymentRoutingStatePort.class).lockAndLoad(7L, id)).isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(PaymentRoutingStatePort.class).flush(7L, id)).isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(ExecutionCommandStore.class).enqueue(message())).isInstanceOf(IllegalTransactionStateException.class);
            verifyNoInteractions(transactions, outboxes, em, statements, notifications, vault, connection);
        });
    }

    @Test
    void outboxStatusFlushStatementAndNotificationPrecedeOneCallerCommit() throws Exception {
        ready();
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            var result = new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class))
                    .execute(status -> ctx.getBean(RouteLockedPaymentUseCase.class).route(command()));
            assertThat(result.immediateDispatchOutboxId()).isNotNull();
            assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
            var order = inOrder(outboxes, lifecycle, transactions, statements, notifications, vault, connection);
            order.verify(outboxes).save(any(KfeExecutionOutboxEntity.class));
            order.verify(lifecycle).transition(eq(id), eq(ExecutionStatus.EXECUTING), eq("KFE_TRANSACTION_EXECUTING"), any());
            order.verify(transactions).findByIdAndUserId(id.value(), 7L);
            order.verify(transactions).saveAndFlush(tx);
            order.verify(statements).record(any());
            order.verify(notifications).notifyPaymentInitiated(7L, id.value(), tx.getSourceWalletId(), "ONCHAIN", 10_000L);
            order.verify(vault).isSubmitOnOutboundEnabled();
            order.verify(connection).commit();
            verify(connection, never()).rollback();
            verifyNoInteractions(internal);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"outbox", "lifecycle", "flush", "statement", "notification"})
    void caughtRoutingFailureMarksTheOuterTransactionRollbackOnly(String stage) throws Exception {
        ready();
        var failure = new IllegalStateException("routing unavailable");
        switch (stage) {
            case "outbox" -> when(outboxes.save(any())).thenThrow(failure);
            case "lifecycle" -> doThrow(failure).when(lifecycle).transition(any(), any(), any(), any());
            case "flush" -> when(transactions.saveAndFlush(tx)).thenThrow(failure);
            case "statement" -> doThrow(failure).when(statements).record(any());
            case "notification" -> doThrow(failure).when(notifications).notifyPaymentInitiated(any(), any(), any(), any(), anyLong());
        }
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThatThrownBy(() -> new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status ->
                    assertThatThrownBy(() -> ctx.getBean(RouteLockedPaymentUseCase.class).route(command())).isSameAs(failure)))
                    .isInstanceOf(UnexpectedRollbackException.class);
            verify(connection).rollback();
            verify(connection, never()).commit();
            verifyNoInteractions(vault);
        });
    }

    private void ready() {
        tx.setUserId(7L); tx.setStatus(KfeTransactionStatus.LOCKED); tx.setRail(KfeRail.ONCHAIN); tx.setDirection(KfeDirection.OUTBOUND);
        tx.setSourceWalletId(UUID.randomUUID()); tx.setGrossAmountSats(10_000L); tx.setReceiverAmountSats(9_910L);
        tx.setNetworkFeeSats(100L); tx.setTotalDebitSats(10_100L); tx.setExternalReference("reference"); tx.setMemo("memo");
        tx.setIdempotencyKey("key"); tx.setQuorumProposalHash("proposal");
        when(transactions.findByIdAndUserIdForUpdate(tx.getId(), 7L)).thenReturn(Optional.of(tx));
        when(transactions.findByIdAndUserId(tx.getId(), 7L)).thenReturn(Optional.of(tx));
        when(lifecycle.transition(any(), any(), any(), any())).thenAnswer(invocation -> {
            tx.setStatus(KfeTransactionStatus.EXECUTING);
            return new PaymentExecutionStatusChanged(id, ExecutionStatus.LOCKED, ExecutionStatus.EXECUTING);
        });
    }
    private RouteLockedPaymentCommand command() { return new RouteLockedPaymentCommand(7L, id, " reference ", " memo ", 12L, 3); }
    private ScheduleExternalExecutionCommand message() { return new ScheduleExternalExecutionCommand(id, new IdempotencyKey("key"), 7L,
            PaymentRail.ONCHAIN, PaymentDirection.OUTBOUND, UUID.randomUUID(), null, 10_000L, 0L, 10_000L, "ref", null, "hash", null, null); }
    private ApplicationContextRunner context(Connection connection) throws Exception {
        var ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        return new ApplicationContextRunner().withAllowCircularReferences(false).withUserConfiguration(Graph.class)
                .withBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(ds))
                .withBean(KfeTransactionRepository.class, () -> transactions).withBean(KfeExecutionOutboxRepository.class, () -> outboxes)
                .withBean(EntityManager.class, () -> em).withBean(ObjectMapper.class, ObjectMapper::new).withBean(KfeHashService.class, KfeHashService::new)
                .withBean(PaymentExecutionLifecycleUseCase.class, () -> lifecycle).withBean(SettleInternalPaymentUseCase.class, () -> internal)
                .withBean(PaymentStatementPort.class, () -> statements).withBean(FinancialNotificationPort.class, () -> notifications)
                .withBean(KfeVaultMeshIntentService.class, () -> vault);
    }
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({PaymentRoutingConfiguration.class, TransactionalPaymentRoutingAdapter.class, JpaPaymentRoutingStateAdapter.class,
            JpaExecutionCommandStoreAdapter.class, LegacyPaymentInitiatedNotificationAdapter.class, LegacyPaymentVaultIntentAdapter.class})
    static class Graph {}
}
