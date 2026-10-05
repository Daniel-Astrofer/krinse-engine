package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalPaymentFundsReservationAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentFundsReservationStateAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentWalletLookupAdapter;
import com.kerosene.kfe.paymentexecution.application.command.ReservePaymentFundsCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentExecutionLifecycleUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.ReservePaymentFundsUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentFundsReservationStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLedgerPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLiquidityPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentWalletLookupPort;
import com.kerosene.kfe.paymentexecution.application.usecase.ReservePaymentFundsService;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aop.support.AopUtils;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Real reservation composition and proxies; every financial effect shares the caller's transaction. */
class PaymentFundsReservationWiringTest {
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final KfeWalletAddressRepository addresses = mock(KfeWalletAddressRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final PaymentLedgerPort ledger = mock(PaymentLedgerPort.class);
    private final PaymentLiquidityPort liquidity = mock(PaymentLiquidityPort.class);
    private final PaymentExecutionLifecycleUseCase lifecycle = mock(PaymentExecutionLifecycleUseCase.class);
    private final KfeTransactionEntity transaction = new KfeTransactionEntity();
    private final KfeWalletEntity source = new KfeWalletEntity();
    private final PaymentExecutionId id = new PaymentExecutionId(transaction.getId());

    @Test
    void graphHasUniquePortsAndRejectsStandaloneWorkBeforeAcquiringResources() throws Exception {
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(ReservePaymentFundsUseCase.class)
                    .hasSingleBean(ReservePaymentFundsService.class).hasSingleBean(PaymentFundsReservationStatePort.class)
                    .hasSingleBean(PaymentWalletLookupPort.class);
            assertThat(AopUtils.isAopProxy(ctx.getBean(ReservePaymentFundsUseCase.class))).isTrue();
            assertThat(AopUtils.isAopProxy(ctx.getBean(PaymentFundsReservationStatePort.class))).isTrue();
            assertThatThrownBy(() -> ctx.getBean(ReservePaymentFundsUseCase.class).reserve(command()))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(PaymentFundsReservationStatePort.class).lockAndLoad(7L, id))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(PaymentWalletLookupPort.class).lockOwnedSource(7L, source.getId()))
                    .isInstanceOf(IllegalTransactionStateException.class);
            verifyNoInteractions(transactions, wallets, addresses, entityManager, ledger, liquidity, lifecycle, connection);
        });
    }

    @Test
    void lightningReservesLedgerThenCapacityAndConfirmsLockedBeforeSingleCommit() throws Exception {
        ready(KfeRail.LIGHTNING, KfeDirection.OUTBOUND);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            return null;
        }).when(ledger).reserve(id, source.getId(), 10_150L);
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            var template = new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class));
            var result = template.execute(status -> ctx.getBean(ReservePaymentFundsUseCase.class).reserve(command()));
            assertThat(result).isEqualTo(new PaymentExecutionStatusChanged(id, ExecutionStatus.QUORUM_SYNC, ExecutionStatus.LOCKED));
            var order = inOrder(transactions, wallets, entityManager, ledger, liquidity, lifecycle, connection);
            order.verify(transactions).findByIdAndUserIdForUpdate(id.value(), 7L);
            order.verify(entityManager).refresh(transaction, LockModeType.PESSIMISTIC_WRITE);
            order.verify(wallets).findByIdAndUserIdForUpdate(source.getId(), 7L);
            order.verify(entityManager).refresh(source, LockModeType.PESSIMISTIC_WRITE);
            order.verify(ledger).reserve(id, source.getId(), 10_150L);
            order.verify(liquidity).reserve(id, 10_150L);
            order.verify(lifecycle).transition(id, ExecutionStatus.LOCKED, "KFE_TRANSACTION_LOCKED",
                    Map.of("proposalHash", "quorum-proposal", "quorumAckCount", 3));
            order.verify(connection).commit();
            verify(connection, times(1)).commit();
            verify(connection, never()).rollback();
            verifyNoInteractions(addresses);
        });
    }

    @ParameterizedTest
    @CsvSource({"ONCHAIN,OUTBOUND", "INTERNAL,INTERNAL"})
    void nonLightningDebitDoesNotReserveLightningCapacity(KfeRail rail, KfeDirection direction) throws Exception {
        ready(rail, direction);
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class))
                    .executeWithoutResult(status -> ctx.getBean(ReservePaymentFundsUseCase.class).reserve(command()));
            verify(ledger).reserve(id, source.getId(), 10_150L);
            verifyNoInteractions(liquidity, addresses);
            verify(connection).commit();
        });
    }

    @ParameterizedTest
    @CsvSource({"ONCHAIN,INBOUND", "LIGHTNING,INBOUND"})
    void inboundTransitionsWithoutSourceOrLiquidityReservation(KfeRail rail, KfeDirection direction) throws Exception {
        ready(rail, direction);
        transaction.setSourceWalletId(null);
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class))
                    .executeWithoutResult(status -> ctx.getBean(ReservePaymentFundsUseCase.class).reserve(command()));
            verifyNoInteractions(wallets, ledger, liquidity, addresses);
            verify(lifecycle).transition(eq(id), eq(ExecutionStatus.LOCKED), eq("KFE_TRANSACTION_LOCKED"), anyMap());
            verify(connection).commit();
        });
    }

    @Test
    void caughtLiquidityFailureStillRollsBackLedgerAndNeverConfirmsLocked() throws Exception {
        ready(KfeRail.LIGHTNING, KfeDirection.OUTBOUND);
        var failure = new IllegalStateException("liquidity unavailable");
        doThrow(failure).when(liquidity).reserve(id, 10_150L);
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            var template = new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class));
            assertThatThrownBy(() -> template.executeWithoutResult(status ->
                    assertThatThrownBy(() -> ctx.getBean(ReservePaymentFundsUseCase.class).reserve(command())).isSameAs(failure)))
                    .isInstanceOf(UnexpectedRollbackException.class);
            verify(ledger).reserve(id, source.getId(), 10_150L);
            verifyNoInteractions(lifecycle);
            verify(connection).rollback();
            verify(connection, never()).commit();
        });
    }

    @Test
    void caughtLedgerFailureCannotCommitOrConsumeLightningCapacity() throws Exception {
        ready(KfeRail.LIGHTNING, KfeDirection.OUTBOUND);
        var failure = new IllegalStateException("ledger unavailable");
        doThrow(failure).when(ledger).reserve(id, source.getId(), 10_150L);
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            var template = new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class));
            assertThatThrownBy(() -> template.executeWithoutResult(status ->
                    assertThatThrownBy(() -> ctx.getBean(ReservePaymentFundsUseCase.class).reserve(command())).isSameAs(failure)))
                    .isInstanceOf(UnexpectedRollbackException.class);
            verifyNoInteractions(liquidity, lifecycle);
            verify(connection).rollback();
            verify(connection, never()).commit();
        });
    }

    @Test
    void unconfirmedTransitionRollsBackBothReservationsEvenWhenCallerCatches() throws Exception {
        ready(KfeRail.LIGHTNING, KfeDirection.OUTBOUND);
        when(lifecycle.transition(any(), any(), any(), any())).thenReturn(null);
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            var template = new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class));
            assertThatThrownBy(() -> template.executeWithoutResult(status ->
                    assertThatThrownBy(() -> ctx.getBean(ReservePaymentFundsUseCase.class).reserve(command()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage("Funds reservation transition was not confirmed.")))
                    .isInstanceOf(UnexpectedRollbackException.class);
            verify(ledger).reserve(id, source.getId(), 10_150L);
            verify(liquidity).reserve(id, 10_150L);
            verify(connection).rollback();
            verify(connection, never()).commit();
        });
    }

    @Test
    void refreshedAlreadyLockedExecutionCannotReserveAgain() throws Exception {
        ready(KfeRail.LIGHTNING, KfeDirection.OUTBOUND);
        doAnswer(invocation -> { transaction.setStatus(KfeTransactionStatus.LOCKED); return null; })
                .when(entityManager).refresh(transaction, LockModeType.PESSIMISTIC_WRITE);
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            var template = new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class));
            assertThatThrownBy(() -> template.executeWithoutResult(status -> ctx.getBean(ReservePaymentFundsUseCase.class).reserve(command())))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Payment is not eligible for funds reservation.");
            verifyNoInteractions(wallets, ledger, liquidity, lifecycle);
            verify(connection).rollback();
            verify(connection, never()).commit();
        });
    }

    @Test
    void walletArchivedWhileWaitingForLockCannotReserveFunds() throws Exception {
        ready(KfeRail.LIGHTNING, KfeDirection.OUTBOUND);
        doAnswer(invocation -> { source.setStatus(KfeWalletStatus.ARCHIVED); return null; })
                .when(entityManager).refresh(source, LockModeType.PESSIMISTIC_WRITE);
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            var template = new TransactionTemplate(ctx.getBean(DataSourceTransactionManager.class));
            assertThatThrownBy(() -> template.executeWithoutResult(status -> ctx.getBean(ReservePaymentFundsUseCase.class).reserve(command())))
                    .isInstanceOf(IllegalStateException.class).hasMessage("source wallet is not active.");
            verifyNoInteractions(ledger, liquidity, lifecycle);
            verify(connection).rollback();
            verify(connection, never()).commit();
        });
    }

    private ApplicationContextRunner context(Connection connection) throws Exception {
        var dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        return new ApplicationContextRunner().withAllowCircularReferences(false).withUserConfiguration(Graph.class)
                .withBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
                .withBean(KfeTransactionRepository.class, () -> transactions)
                .withBean(KfeWalletRepository.class, () -> wallets)
                .withBean(KfeWalletAddressRepository.class, () -> addresses)
                .withBean(EntityManager.class, () -> entityManager)
                .withBean(PaymentLedgerPort.class, () -> ledger)
                .withBean(PaymentLiquidityPort.class, () -> liquidity)
                .withBean(PaymentExecutionLifecycleUseCase.class, () -> lifecycle);
    }

    private void ready(KfeRail rail, KfeDirection direction) {
        source.setUserId(7L);
        source.setKind(KfeWalletKind.INTERNAL);
        source.setStatus(KfeWalletStatus.ACTIVE);
        source.setSpendable(true);
        transaction.setUserId(7L);
        transaction.setStatus(KfeTransactionStatus.QUORUM_SYNC);
        transaction.setRail(rail);
        transaction.setDirection(direction);
        transaction.setSourceWalletId(source.getId());
        transaction.setTotalDebitSats(10_150L);
        transaction.setQuorumProposalHash("quorum-proposal");
        transaction.setQuorumAckCount(3);
        when(transactions.findByIdAndUserIdForUpdate(id.value(), 7L)).thenReturn(Optional.of(transaction));
        when(wallets.findByIdAndUserIdForUpdate(source.getId(), 7L)).thenReturn(Optional.of(source));
        when(lifecycle.transition(any(), any(), any(), any()))
                .thenReturn(new PaymentExecutionStatusChanged(id, ExecutionStatus.QUORUM_SYNC, ExecutionStatus.LOCKED));
    }

    private ReservePaymentFundsCommand command() { return new ReservePaymentFundsCommand(7L, id); }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({PaymentFundsReservationConfiguration.class, TransactionalPaymentFundsReservationAdapter.class,
            JpaPaymentFundsReservationStateAdapter.class, JpaPaymentWalletLookupAdapter.class})
    static class Graph {}
}
