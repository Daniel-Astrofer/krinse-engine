package com.kerosene.kfe.paymentexecution.config;

import com.kerosene.kfe.paymentexecution.adapters.in.transaction.TransactionalPaymentCancellationAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.audit.KfePaymentCancellationAuditAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.audit.KfePaymentRequestCancellationAuditAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentCancellationStateAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentCancellationQueryAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentRequestCancellationStateAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.messaging.LegacyPaymentCancellationNotificationAdapter;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentCancellationHintsUseCase;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentExecutionQueryAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.statement.LegacyPaymentStatementAdapter;
import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentRequestCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.CancelPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.CancelPaymentRequestUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.application.usecase.CancelPaymentEffectsService;
import com.kerosene.kfe.paymentexecution.application.usecase.CancelPaymentService;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestStatus;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Exercises the real cancellation bean graph, including mapper/statement and transaction proxies. */
class PaymentCancellationWiringTest {
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final PaymentCancellationFencePort fence = mock(PaymentCancellationFencePort.class);
    private final PaymentRequestCancellationLockPort requestLock = mock(PaymentRequestCancellationLockPort.class);
    private final PaymentLedgerPort ledger = mock(PaymentLedgerPort.class);
    private final KfeStatementService statements = mock(KfeStatementService.class);
    private final KfePaymentRequestRepository requests = mock(KfePaymentRequestRepository.class);
    private final KfeAuditLogService audit = mock(KfeAuditLogService.class);
    private final KfeDashboardPublisher dashboard = mock(KfeDashboardPublisher.class);

    private ApplicationContextRunner context(Connection connection) throws Exception {
        var dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        return new ApplicationContextRunner()
                .withAllowCircularReferences(false)
                .withUserConfiguration(CancellationGraph.class)
                .withBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
                .withBean(KfeTransactionRepository.class, () -> transactions)
                .withBean(KfePaymentRequestRepository.class, () -> requests)
                .withBean(KfeWalletRepository.class, () -> mock(KfeWalletRepository.class))
                .withBean(KfeWalletAddressRepository.class, () -> mock(KfeWalletAddressRepository.class))
                .withBean(KfeAuditLogService.class, () -> audit)
                .withBean(KfeDashboardPublisher.class, () -> dashboard)
                .withBean(KfeStatementService.class, () -> statements)
                .withBean(PaymentLedgerPort.class, () -> ledger)
                .withBean(PaymentLiquidityPort.class, () -> mock(PaymentLiquidityPort.class))
                .withBean(PaymentInvoiceCancellationPort.class, () -> mock(PaymentInvoiceCancellationPort.class))
                .withBean(PaymentCancellationFencePort.class, () -> fence)
                .withBean(PaymentRequestCancellationLockPort.class, () -> requestLock)
                .withBean(PaymentExecutionRepository.class, () -> mock(PaymentExecutionRepository.class))
                .withBean(PaymentExecutionAuditPort.class, () -> mock(PaymentExecutionAuditPort.class));
    }

    @Test
    void resolvesSingleBeansWithoutEagerCyclesAndEnforcesTransactionBeforeLoadingState() throws Exception {
        var connection = mock(Connection.class);
        context(connection).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(CancelPaymentUseCase.class)
                    .hasSingleBean(CancelPaymentRequestUseCase.class)
                    .hasSingleBean(PaymentCancellationHintsUseCase.class)
                    .hasSingleBean(CancelPaymentService.class)
                    .hasSingleBean(CancelPaymentEffectsService.class)
                    .hasSingleBean(PaymentCancellationStatePort.class)
                    .hasSingleBean(PaymentCancellationAuditPort.class)
                    .hasSingleBean(PaymentRequestCancellationStatePort.class)
                    .hasSingleBean(PaymentRequestCancellationAuditPort.class)
                    .hasSingleBean(PaymentCancellationNotificationPort.class);
            assertThat(ctx.getBean(CancelPaymentUseCase.class))
                    .isSameAs(ctx.getBean(CancelPaymentRequestUseCase.class));
            assertThat(AopUtils.isAopProxy(ctx.getBean(CancelPaymentUseCase.class))).isTrue();
            assertThat(AopUtils.isAopProxy(ctx.getBean(CancelPaymentService.class))).isFalse();
            assertThat(AopUtils.isAopProxy(ctx.getBean(PaymentCancellationStatePort.class))).isTrue();
            assertThat(AopUtils.isAopProxy(ctx.getBean(PaymentCancellationAuditPort.class))).isTrue();
            assertThat(AopUtils.isAopProxy(ctx.getBean(PaymentRequestCancellationStatePort.class))).isTrue();
            assertThat(AopUtils.isAopProxy(ctx.getBean(PaymentRequestCancellationAuditPort.class))).isTrue();
            assertThat(AopUtils.isAopProxy(ctx.getBean(PaymentCancellationNotificationPort.class))).isTrue();
            assertThatThrownBy(() -> ctx.getBean(CancelPaymentEffectsService.class)
                    .cancel(new PaymentExecutionId(UUID.randomUUID()), "cancel"))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(PaymentRequestCancellationStatePort.class)
                    .load(7L, UUID.randomUUID()))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(PaymentRequestCancellationStatePort.class)
                    .markCancelled(null))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(PaymentRequestCancellationAuditPort.class)
                    .recordCancelled(null))
                    .isInstanceOf(IllegalTransactionStateException.class);
            assertThatThrownBy(() -> ctx.getBean(PaymentCancellationNotificationPort.class)
                    .publishAfterCommit(7L))
                    .isInstanceOf(IllegalTransactionStateException.class);
            verifyNoInteractions(transactions, requests, ledger, statements, audit, dashboard);
        });
    }

    @Test
    void publicUseCaseTraversesTheNewEffectsAndMapsTheCommittedResult() throws Exception {
        var tx = cancellableTransaction();
        var id = new PaymentExecutionId(tx.getId());
        var connection = mock(Connection.class);

        context(connection).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var result = ctx.getBean(CancelPaymentUseCase.class).cancel(new CancelPaymentCommand(7L, id));
            assertThat(result.status()).isEqualTo(ExecutionStatus.FAILED);
            assertThat(result.failureCode()).isEqualTo("USER_CANCELLED");
            assertThat(result.cancellable()).isFalse();
            var order = inOrder(fence, ledger, transactions, statements, connection);
            order.verify(fence).fence(List.of(id));
            order.verify(transactions, calls(2)).findById(tx.getId());
            order.verify(ledger).releaseReserved(id, tx.getSourceWalletId(), 5_000L);
            order.verify(transactions, calls(1)).findById(tx.getId());
            order.verify(transactions).save(tx);
            order.verify(transactions, calls(1)).findById(tx.getId());
            order.verify(statements).recordUserStatement(eq(7L), eq(tx.getSourceWalletId()), eq(tx), any());
            order.verify(connection).commit();
            verify(connection, never()).rollback();
        });
    }

    @Test
    void failedFinancialEffectRollsBackThePublicTransactionWithoutPublishingSuccess() throws Exception {
        var tx = cancellableTransaction();
        var id = new PaymentExecutionId(tx.getId());
        var connection = mock(Connection.class);
        doThrow(new IllegalStateException("ledger unavailable"))
                .when(ledger).releaseReserved(id, tx.getSourceWalletId(), 5_000L);

        context(connection).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThatThrownBy(() -> ctx.getBean(CancelPaymentUseCase.class)
                    .cancel(new CancelPaymentCommand(7L, id)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("ledger unavailable");
            verify(fence).fence(List.of(id));
            verify(transactions, never()).save(any());
            verifyNoInteractions(statements, audit, dashboard);
            verify(connection).rollback();
            verify(connection, never()).commit();
        });
    }

    @Test
    void requestUseCaseLocksWritesAuditsAndCommitsThroughTheSameTransactionalAdapter() throws Exception {
        var request = cancellableRequest();
        var connection = mock(Connection.class);

        context(connection).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var result = ctx.getBean(CancelPaymentRequestUseCase.class)
                    .cancelPaymentRequest(new CancelPaymentRequestCommand(7L, request.getId()));
            assertThat(result).isEqualTo(request.getId());
            assertThat(request.getStatus()).isEqualTo(KfePaymentRequestStatus.CANCELLED);
            var order = inOrder(requestLock, requests, fence, audit, dashboard, connection);
            order.verify(requestLock).lock(7L, request.getId());
            order.verify(requests, calls(2)).findByIdAndUserId(request.getId(), 7L);
            order.verify(fence).fence(List.of());
            order.verify(requests, calls(1)).findByIdAndUserId(request.getId(), 7L);
            order.verify(requests).save(request);
            order.verify(audit).record(eq("KFE_PAYMENT_REQUEST_CANCELLED"), isNull(),
                    eq(request.getWalletId()), isNull(), isNull(), anyMap());
            order.verify(dashboard).publishAfterCommit(7L);
            order.verify(connection).commit();
            verify(connection, never()).rollback();
            verifyNoInteractions(ledger, statements);
        });
    }

    @Test
    void requestAuditFailureRollsBackAfterStateWriteAndDoesNotPublishSuccess() throws Exception {
        var request = cancellableRequest();
        var connection = mock(Connection.class);
        doThrow(new IllegalStateException("audit unavailable")).when(audit)
                .record(eq("KFE_PAYMENT_REQUEST_CANCELLED"), isNull(), eq(request.getWalletId()),
                        isNull(), isNull(), anyMap());

        context(connection).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThatThrownBy(() -> ctx.getBean(CancelPaymentRequestUseCase.class)
                    .cancelPaymentRequest(new CancelPaymentRequestCommand(7L, request.getId())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("audit unavailable");
            verify(requests).save(request);
            verify(connection).rollback();
            verify(connection, never()).commit();
            verifyNoInteractions(ledger, statements, dashboard);
        });
    }

    private KfePaymentRequestEntity cancellableRequest() {
        var request = new KfePaymentRequestEntity();
        request.setUserId(7L);
        request.setWalletId(UUID.randomUUID());
        request.setPublicId("request-test-cancel");
        request.setRail(KfeRail.ONCHAIN);
        request.setStatus(KfePaymentRequestStatus.OPEN);
        when(requests.findByIdAndUserId(request.getId(), 7L)).thenReturn(Optional.of(request));
        return request;
    }

    private KfeTransactionEntity cancellableTransaction() {
        var tx = new KfeTransactionEntity();
        tx.setUserId(7L);
        tx.setIdempotencyKey("test-cancel");
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);
        tx.setStatus(KfeTransactionStatus.LOCKED);
        tx.setSourceWalletId(UUID.randomUUID());
        tx.setTotalDebitSats(5_000L);
        when(transactions.findParticipantVisibleById(tx.getId(), 7L, KfeRail.INTERNAL, KfeDirection.INTERNAL))
                .thenReturn(Optional.of(tx));
        when(transactions.findById(tx.getId())).thenReturn(Optional.of(tx));
        return tx;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({PaymentExecutionConfiguration.class,
            TransactionalPaymentCancellationAdapter.class, LegacyPaymentCancellationNotificationAdapter.class,
            KfeResponseMapper.class, JpaPaymentCancellationStateAdapter.class,
            KfePaymentCancellationAuditAdapter.class, JpaPaymentRequestCancellationStateAdapter.class,
            KfePaymentRequestCancellationAuditAdapter.class,
            JpaPaymentExecutionQueryAdapter.class, LegacyPaymentStatementAdapter.class,
            JpaPaymentCancellationQueryAdapter.class})
    static class CancellationGraph {}
}
