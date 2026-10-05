package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.adapters.out.persistence.model.audit.*;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.*;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.*;
import com.kerosene.kfe.adapters.out.persistence.model.messaging.*;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.*;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.*;
import com.kerosene.kfe.adapters.out.persistence.model.shared.*;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.*;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalInternalPaymentSettlementAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.messaging.LegacyInternalPaymentNotificationAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaInternalPaymentSettlementStateAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentIntentStoreAdapter;
import com.kerosene.kfe.paymentexecution.application.command.SettleInternalPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.*;
import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentInternalSettlementWiringTest {
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final PaymentLedgerPort ledger = mock(PaymentLedgerPort.class);
    private final PaymentFeeSettlementPort fees = mock(PaymentFeeSettlementPort.class);
    private final PaymentStatementPort statements = mock(PaymentStatementPort.class);
    private final FinancialNotificationPort notifications = mock(FinancialNotificationPort.class);
    private final PaymentExecutionLifecycleUseCase lifecycle = mock(PaymentExecutionLifecycleUseCase.class);
    private final KfeTransactionEntity tx = new KfeTransactionEntity();
    private final PaymentExecutionId id = new PaymentExecutionId(tx.getId());

    @Test
    void graphHasUniquePortsAndRefusesStandaloneSettlement() throws Exception {
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(CreatePaymentIntentUseCase.class)
                    .hasSingleBean(SettleInternalPaymentUseCase.class);
            assertThatThrownBy(() -> ctx.getBean(SettleInternalPaymentUseCase.class).settle(command()))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(InternalPaymentSettlementStatePort.class).lockAndLoad(7L, id))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(InternalPaymentNotificationPort.class).sent(7L, id, tx.getSourceWalletId(), 1L))
                    .isInstanceOf(IllegalTransactionStateException.class);
            verifyNoInteractions(transactions, wallets, ledger, notifications);
        });
    }

    @Test
    void settlementCommitsOnlyWithItsOwningTransaction() throws Exception {
        ready();
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            var template = new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class));
            template.executeWithoutResult(status -> ctx.getBean(SettleInternalPaymentUseCase.class).settle(command()));
            var order = inOrder(ledger, fees, statements, notifications, connection);
            order.verify(ledger).settleReservedDebit(id, tx.getSourceWalletId(), 10_000L);
            order.verify(ledger).creditAvailable(id, tx.getDestinationWalletId(), 9_910L);
            order.verify(fees).settleFee(id);
            order.verify(statements).record(any());
            order.verify(notifications).notifyInternalTransferSent(7L, id.value(), tx.getSourceWalletId(), 10_000L);
            order.verify(statements).record(any());
            order.verify(notifications).notifyInternalTransferReceived(8L, id.value(), tx.getDestinationWalletId(), 9_910L);
            order.verify(connection).commit();
            verify(connection, never()).rollback();
        });
    }

    @Test
    void caughtSettlementFailureStillMarksOuterTransactionRollbackOnly() throws Exception {
        ready();
        var failure = new IllegalStateException("credit unavailable");
        doThrow(failure).when(ledger).creditAvailable(id, tx.getDestinationWalletId(), 9_910L);
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            var template = new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class));
            assertThatThrownBy(() -> template.executeWithoutResult(status -> {
                assertThatThrownBy(() -> ctx.getBean(SettleInternalPaymentUseCase.class).settle(command())).isSameAs(failure);
            })).isInstanceOf(UnexpectedRollbackException.class);
            verify(connection).rollback();
            verify(connection, never()).commit();
            verifyNoInteractions(lifecycle, fees, statements, notifications);
        });
    }

    private ApplicationContextRunner context(Connection connection) throws Exception {
        var ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        return new ApplicationContextRunner().withAllowCircularReferences(false).withUserConfiguration(Graph.class)
                .withBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(ds))
                .withBean(KfeTransactionRepository.class, () -> transactions)
                .withBean(KfeWalletRepository.class, () -> wallets)
                .withBean(EntityManager.class, () -> mock(EntityManager.class))
                .withBean(PaymentLedgerPort.class, () -> ledger)
                .withBean(PaymentFeeSettlementPort.class, () -> fees)
                .withBean(PaymentStatementPort.class, () -> statements)
                .withBean(FinancialNotificationPort.class, () -> notifications)
                .withBean(PaymentExecutionLifecycleUseCase.class, () -> lifecycle);
    }

    private void ready() {
        var source = new KfeWalletEntity(); source.setUserId(7L);
        var destination = new KfeWalletEntity(); destination.setUserId(8L);
        tx.setUserId(7L); tx.setRail(KfeRail.INTERNAL); tx.setDirection(KfeDirection.INTERNAL);
        tx.setStatus(KfeTransactionStatus.LOCKED); tx.setTotalDebitSats(10_000L); tx.setReceiverAmountSats(9_910L);
        tx.setSourceWalletId(source.getId()); tx.setDestinationWalletId(destination.getId());
        when(transactions.findByIdAndUserIdForUpdate(tx.getId(), 7L)).thenReturn(Optional.of(tx));
        when(wallets.findByIdAndUserId(source.getId(), 7L)).thenReturn(Optional.of(source));
        when(wallets.findById(destination.getId())).thenReturn(Optional.of(destination));
        when(lifecycle.transition(any(), any(), any(), any())).thenReturn(new PaymentExecutionStatusChanged(id, ExecutionStatus.LOCKED, ExecutionStatus.SETTLED));
    }

    private SettleInternalPaymentCommand command() { return new SettleInternalPaymentCommand(7L, id); }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({PaymentIntentConfiguration.class, JpaPaymentIntentStoreAdapter.class,
            PaymentInternalSettlementConfiguration.class, TransactionalInternalPaymentSettlementAdapter.class,
            JpaInternalPaymentSettlementStateAdapter.class, LegacyInternalPaymentNotificationAdapter.class})
    static class Graph {}
}
