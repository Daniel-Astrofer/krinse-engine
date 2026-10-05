package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentRequestCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionQueryRepository;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.application.usecase.CancelPaymentService;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentRequestCancellationStateAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.audit.KfePaymentRequestCancellationAuditAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.messaging.LegacyPaymentCancellationNotificationAdapter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestStatus;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentInvoiceCancellationPort;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLedgerPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentStatementPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLiquidityPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationFencePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationLockPort;
import com.kerosene.kfe.paymentexecution.application.command.RecordPaymentStatementCommand;
import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentInvoiceCommand;
import com.kerosene.kfe.paymentexecution.application.usecase.CancelPaymentEffectsService;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentCancellationStateAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaPaymentCancellationQueryAdapter;
import com.kerosene.kfe.paymentexecution.application.usecase.PaymentCancellationHintsService;
import com.kerosene.kfe.paymentexecution.adapters.out.audit.KfePaymentCancellationAuditAdapter;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentCancellationRejected;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionalPaymentCancellationAdapterTest {

    @Mock
    private KfeTransactionRepository transactionRepository;
    @Mock
    private KfePaymentRequestRepository paymentRequestRepository;
    @Mock
    private PaymentLedgerPort ledgerPort;
    @Mock
    private PaymentLiquidityPort liquidityPort;
    @Mock
    private PaymentCancellationFencePort cancellationFence;
    @Mock
    private PaymentRequestCancellationLockPort paymentRequestLock;
    @Mock
    private PaymentStatementPort statementPort;
    @Mock
    private PaymentExecutionQueryRepository results;
    @Mock
    private KfeDashboardPublisher dashboardPublisher;
    @Mock
    private KfeAuditLogService auditLogService;
    @Mock
    private PaymentInvoiceCancellationPort invoiceCancellationPort;

    private TransactionalPaymentCancellationAdapter service;
    private PaymentCancellationHintsService cancellationHints;

    @BeforeEach
    void setUp() {
        var cancellationLookup = new JpaPaymentCancellationQueryAdapter(transactionRepository, paymentRequestRepository);
        cancellationHints = new PaymentCancellationHintsService(cancellationLookup);
        var state = new JpaPaymentCancellationStateAdapter(transactionRepository);
        service = new TransactionalPaymentCancellationAdapter(new CancelPaymentService(
                cancellationLookup, cancellationHints, state,
                new JpaPaymentRequestCancellationStateAdapter(paymentRequestRepository),
                paymentRequestLock, cancellationLookup, cancellationFence, invoiceCancellationPort,
                new KfePaymentRequestCancellationAuditAdapter(auditLogService),
                new CancelPaymentEffectsService(state, ledgerPort, liquidityPort, statementPort,
                        new KfePaymentCancellationAuditAdapter(auditLogService)),
                new LegacyPaymentCancellationNotificationAdapter(dashboardPublisher), results));
    }

    @Test
    void openPaymentRequestIsCancellableViaHints() {
        UUID txId = UUID.randomUUID();
        UUID prId = UUID.randomUUID();
        KfeTransactionEntity tx = baseTx(txId, KfeTransactionStatus.VALIDATING);
        tx.setIdempotencyKey("payment-request:" + prId + ":txid");

        KfePaymentRequestEntity pr = paymentRequest(prId, KfePaymentRequestStatus.OPEN);

        when(paymentRequestRepository.findByPaidTransactionIdAndUserId(txId, 7L)).thenReturn(Optional.empty());
        when(paymentRequestRepository.findByIdAndUserId(prId, 7L)).thenReturn(Optional.of(pr));

        var hints = cancellationHints.hintsFor(7L, new PaymentExecutionId(tx.getId()));
        assertThat(hints.cancellable()).isTrue();
        assertThat(hints.cancelTarget()).isEqualTo("PAYMENT_REQUEST");
        assertThat(hints.paymentRequestId()).isEqualTo(prId);
    }

    @Test
    void settledTransactionIsNotCancellable() {
        KfeTransactionEntity tx = baseTx(UUID.randomUUID(), KfeTransactionStatus.SETTLED);
        when(paymentRequestRepository.findByPaidTransactionIdAndUserId(tx.getId(), 7L))
                .thenReturn(Optional.empty());

        var hints = cancellationHints.hintsFor(7L, new PaymentExecutionId(tx.getId()));
        assertThat(hints.cancellable()).isFalse();
    }

    @Test
    void cancelFailsPendingTransactionAndReleasesReserve() {
        UUID txId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        KfeTransactionEntity tx = baseTx(txId, KfeTransactionStatus.LOCKED);
        tx.setSourceWalletId(walletId);
        tx.setTotalDebitSats(5_000L);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);

        when(transactionRepository.findParticipantVisibleById(
                eq(txId), eq(7L), eq(KfeRail.INTERNAL), eq(KfeDirection.INTERNAL)))
                .thenReturn(Optional.of(tx));
        when(paymentRequestRepository.findByPaidTransactionIdAndUserId(txId, 7L)).thenReturn(Optional.empty());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findById(txId)).thenReturn(Optional.of(tx));

        service.cancel(new CancelPaymentCommand(7L, new PaymentExecutionId(txId)));

        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.FAILED);
        assertThat(tx.getFailureCode()).isEqualTo("USER_CANCELLED");
        verify(ledgerPort).releaseReserved(new PaymentExecutionId(txId), walletId, 5_000L);
        verify(statementPort).record(
                new RecordPaymentStatementCommand(7L, new PaymentExecutionId(txId), walletId, null, true));
        verify(dashboardPublisher).publishAfterCommit(7L);
        var order = inOrder(cancellationFence, ledgerPort, statementPort);
        order.verify(cancellationFence).fence(List.of(new PaymentExecutionId(txId)));
        order.verify(ledgerPort).releaseReserved(new PaymentExecutionId(txId), walletId, 5_000L);
        order.verify(statementPort).record(any());
    }

    @Test
    void internalReceiverCannotCancelTheSendersPaymentDespiteReadVisibility() {
        UUID txId = UUID.randomUUID();
        var tx = baseTx(txId, KfeTransactionStatus.LOCKED);
        tx.setUserId(8L);
        tx.setRail(KfeRail.INTERNAL);
        tx.setDirection(KfeDirection.INTERNAL);

        assertThatThrownBy(() -> service.cancel(new CancelPaymentCommand(7L, new PaymentExecutionId(txId))))
                .isInstanceOf(IllegalStateException.class);

        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.LOCKED);
        verifyNoInteractions(paymentRequestRepository, cancellationFence, paymentRequestLock,
                ledgerPort, liquidityPort, statementPort, auditLogService, dashboardPublisher, results);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void ownershipRefreshedByFenceIsRevalidatedBeforeReleasingFunds() {
        UUID txId = UUID.randomUUID();
        var tx = baseTx(txId, KfeTransactionStatus.LOCKED);
        doAnswer(invocation -> {
            tx.setUserId(8L);
            return null;
        }).when(cancellationFence).fence(List.of(new PaymentExecutionId(txId)));

        assertThatThrownBy(() -> service.cancel(new CancelPaymentCommand(7L, new PaymentExecutionId(txId))))
                .isInstanceOf(PaymentCancellationRejected.class);
        verifyNoInteractions(ledgerPort, liquidityPort, statementPort, auditLogService, dashboardPublisher, results);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void unknownPaidTransactionCannotBeIgnoredWhenCancellingAnOpenRequest() {
        UUID prId = UUID.randomUUID();
        var pr = paymentRequest(prId, KfePaymentRequestStatus.OPEN);
        pr.setPaidTransactionId(UUID.randomUUID());
        when(paymentRequestRepository.findByIdAndUserId(prId, 7L)).thenReturn(Optional.of(pr));

        assertThatThrownBy(() -> service.cancelPaymentRequest(new CancelPaymentRequestCommand(7L, prId)))
                .isInstanceOf(PaymentCancellationRejected.class);
        assertThat(pr.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
        verifyNoInteractions(cancellationFence, invoiceCancellationPort, ledgerPort, liquidityPort,
                statementPort, auditLogService, dashboardPublisher);
        verify(paymentRequestRepository, never()).save(any());
    }

    @Test
    void requestCannotReleasePaymentWhoseOwnerChangedWhileWaitingForTheFence() {
        UUID prId = UUID.randomUUID();
        var pr = paymentRequest(prId, KfePaymentRequestStatus.OPEN);
        var tx = baseTx(UUID.randomUUID(), KfeTransactionStatus.VALIDATING);
        when(paymentRequestRepository.findByIdAndUserId(prId, 7L)).thenReturn(Optional.of(pr));
        when(transactionRepository.findByUserIdAndIdempotencyKeyStartingWith(7L, "payment-request:" + prId + ":"))
                .thenReturn(List.of(tx));
        doAnswer(invocation -> {
            tx.setUserId(8L);
            return null;
        }).when(cancellationFence).fence(List.of(new PaymentExecutionId(tx.getId())));

        assertThatThrownBy(() -> service.cancelPaymentRequest(new CancelPaymentRequestCommand(7L, prId)))
                .isInstanceOf(PaymentCancellationRejected.class);
        assertThat(pr.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
        verifyNoInteractions(invoiceCancellationPort, ledgerPort, liquidityPort, statementPort,
                auditLogService, dashboardPublisher);
        verify(paymentRequestRepository, never()).save(any());
    }

    @Test
    void claimedPaymentCannotReleaseFundsOrRecordCancellation() {
        UUID txId = UUID.randomUUID();
        KfeTransactionEntity tx = baseTx(txId, KfeTransactionStatus.EXECUTING);
        tx.setSourceWalletId(UUID.randomUUID());
        tx.setTotalDebitSats(5_000L);
        tx.setDirection(KfeDirection.OUTBOUND);
        when(transactionRepository.findParticipantVisibleById(
                txId, 7L, KfeRail.INTERNAL, KfeDirection.INTERNAL)).thenReturn(Optional.of(tx));
        doThrow(new PaymentCancellationRejected()).when(cancellationFence)
                .fence(List.of(new PaymentExecutionId(txId)));

        assertThatThrownBy(() -> service.cancel(new CancelPaymentCommand(7L, new PaymentExecutionId(txId))))
                .isInstanceOf(PaymentCancellationRejected.class);

        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(ledgerPort, liquidityPort, statementPort, auditLogService,
                dashboardPublisher, results);
    }

    @Test
    void statusRefreshedByFenceIsRevalidatedBeforeFinancialEffects() {
        UUID txId = UUID.randomUUID();
        KfeTransactionEntity tx = baseTx(txId, KfeTransactionStatus.EXECUTING);
        when(transactionRepository.findParticipantVisibleById(
                txId, 7L, KfeRail.INTERNAL, KfeDirection.INTERNAL)).thenReturn(Optional.of(tx));
        doAnswer(invocation -> {
            tx.setStatus(KfeTransactionStatus.SETTLED);
            return null;
        }).when(cancellationFence).fence(List.of(new PaymentExecutionId(txId)));

        assertThatThrownBy(() -> service.cancel(new CancelPaymentCommand(7L, new PaymentExecutionId(txId))))
                .isInstanceOf(PaymentCancellationRejected.class);
        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(ledgerPort, liquidityPort, statementPort, auditLogService,
                dashboardPublisher, results);
    }

    @Test
    void liquidityReleaseFailurePreventsSuccessfulCancellation() {
        UUID txId = UUID.randomUUID();
        KfeTransactionEntity tx = baseTx(txId, KfeTransactionStatus.VALIDATING);
        tx.setRail(KfeRail.LIGHTNING);
        tx.setDirection(KfeDirection.OUTBOUND);
        when(transactionRepository.findParticipantVisibleById(
                txId, 7L, KfeRail.INTERNAL, KfeDirection.INTERNAL)).thenReturn(Optional.of(tx));
        doThrow(new IllegalStateException("Liquidity store unavailable")).when(liquidityPort)
                .release(new PaymentExecutionId(txId));

        assertThatThrownBy(() -> service.cancel(new CancelPaymentCommand(7L, new PaymentExecutionId(txId))))
                .isInstanceOf(IllegalStateException.class).hasMessage("Liquidity store unavailable");
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.VALIDATING);
        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(statementPort, auditLogService, dashboardPublisher, results);
    }

    @Test
    void reserveReleaseFailureDoesNotRecordSuccessfulCancellation() {
        UUID txId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        KfeTransactionEntity tx = baseTx(txId, KfeTransactionStatus.LOCKED);
        tx.setSourceWalletId(walletId);
        tx.setTotalDebitSats(5_000L);
        tx.setDirection(KfeDirection.OUTBOUND);
        var failure = new IllegalStateException("Insufficient locked balance.");

        when(transactionRepository.findParticipantVisibleById(
                txId, 7L, KfeRail.INTERNAL, KfeDirection.INTERNAL))
                .thenReturn(Optional.of(tx));
        doThrow(failure).when(ledgerPort)
                .releaseReserved(new PaymentExecutionId(txId), walletId, 5_000L);

        assertThatThrownBy(() -> service.cancel(new CancelPaymentCommand(7L, new PaymentExecutionId(txId)))).isSameAs(failure);

        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.LOCKED);
        assertThat(tx.getFailureCode()).isNull();
        assertThat(tx.getFailureMessage()).isNull();
        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(statementPort, auditLogService, dashboardPublisher,
                liquidityPort, results);
    }

    @Test
    void statementFailureRollsBackCancellationAndSuppressesAfterCommitEvents() throws Exception {
        UUID txId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        KfeTransactionEntity tx = baseTx(txId, KfeTransactionStatus.LOCKED);
        tx.setSourceWalletId(walletId);
        tx.setTotalDebitSats(5_000L);
        tx.setDirection(KfeDirection.OUTBOUND);
        var failure = new IllegalStateException("Statement store unavailable.");
        var synchronization = mock(TransactionSynchronization.class);
        var fixture = transactionalFixture();
        var statement = new RecordPaymentStatementCommand(
                7L, new PaymentExecutionId(txId), walletId, null, true);

        when(transactionRepository.findParticipantVisibleById(
                txId, 7L, KfeRail.INTERNAL, KfeDirection.INTERNAL))
                .thenReturn(Optional.of(tx));
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            TransactionSynchronizationManager.registerSynchronization(synchronization);
            return null;
        }).when(ledgerPort).releaseReserved(new PaymentExecutionId(txId), walletId, 5_000L);
        doThrow(failure).when(statementPort).record(statement);

        assertThatThrownBy(() -> fixture.service().cancel(new CancelPaymentCommand(7L, new PaymentExecutionId(txId)))).isSameAs(failure);

        var order = inOrder(ledgerPort, transactionRepository, statementPort, fixture.connection());
        order.verify(ledgerPort).releaseReserved(new PaymentExecutionId(txId), walletId, 5_000L);
        order.verify(transactionRepository).save(tx);
        order.verify(statementPort).record(statement);
        order.verify(fixture.connection()).rollback();
        verify(fixture.connection(), never()).commit();
        verify(synchronization, never()).afterCommit();
        verify(synchronization).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        verifyNoInteractions(auditLogService, dashboardPublisher, results);
    }

    @Test
    void cancelOpenPaymentRequestFailsRelatedValidatingTx() {
        UUID prId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        KfePaymentRequestEntity pr = paymentRequest(prId, KfePaymentRequestStatus.OPEN);
        pr.setPublicId("abc");
        pr.setRail(KfeRail.LIGHTNING);
        pr.setPaymentHash("deadbeef");
        pr.setWalletId(UUID.randomUUID());

        KfeTransactionEntity related = baseTx(txId, KfeTransactionStatus.VALIDATING);
        related.setDestinationWalletId(pr.getWalletId());
        related.setDirection(KfeDirection.INBOUND);
        related.setIdempotencyKey("payment-request:" + prId + ":txid");

        when(paymentRequestRepository.findByIdAndUserId(prId, 7L)).thenReturn(Optional.of(pr));
        when(paymentRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceCancellationPort.cancel(any())).thenReturn(true);
        when(transactionRepository.findByUserIdAndIdempotencyKeyStartingWith(
                7L, "payment-request:" + prId + ":")).thenReturn(List.of(related));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID cancelled = service.cancelPaymentRequest(new CancelPaymentRequestCommand(7L, prId));

        assertThat(cancelled).isEqualTo(prId);
        assertThat(pr.getStatus()).isEqualTo(KfePaymentRequestStatus.CANCELLED);
        assertThat(related.getStatus()).isEqualTo(KfeTransactionStatus.FAILED);
        assertThat(related.getFailureCode()).isEqualTo("USER_CANCELLED");
        verify(invoiceCancellationPort).cancel(any());
        verify(dashboardPublisher).publishAfterCommit(7L);
    }

    @Test
    void cancelThrowsWhenNotCancellable() {
        UUID txId = UUID.randomUUID();
        KfeTransactionEntity tx = baseTx(txId, KfeTransactionStatus.SETTLED);
        when(transactionRepository.findParticipantVisibleById(
                eq(txId), eq(7L), eq(KfeRail.INTERNAL), eq(KfeDirection.INTERNAL)))
                .thenReturn(Optional.of(tx));
        when(paymentRequestRepository.findByPaidTransactionIdAndUserId(txId, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(new CancelPaymentCommand(7L, new PaymentExecutionId(txId))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void paymentRequestLocksAndFencesEveryRelatedPaymentBeforeCancellingInvoice() {
        UUID prId = UUID.randomUUID();
        KfePaymentRequestEntity pr = paymentRequest(prId, KfePaymentRequestStatus.OPEN);
        pr.setRail(KfeRail.LIGHTNING);
        pr.setPaymentHash("invoice-hash");
        var first = baseTx(UUID.randomUUID(), KfeTransactionStatus.VALIDATING);
        var second = baseTx(UUID.randomUUID(), KfeTransactionStatus.VALIDATING);
        var third = baseTx(UUID.randomUUID(), KfeTransactionStatus.VALIDATING);
        pr.setPaidTransactionId(first.getId());
        when(paymentRequestRepository.findByIdAndUserId(prId, 7L)).thenReturn(Optional.of(pr));
        when(transactionRepository.findByIdAndUserId(first.getId(), 7L)).thenReturn(Optional.of(first));
        when(transactionRepository.findByUserIdAndIdempotencyKeyStartingWith(7L, "payment-request:" + prId + ":"))
                .thenReturn(List.of(first, second));
        when(transactionRepository.findByUserIdAndExternalReference(7L, pr.getPublicId()))
                .thenReturn(List.of(second, third));
        when(invoiceCancellationPort.cancel(any())).thenReturn(true);
        when(paymentRequestRepository.save(pr)).thenReturn(pr);

        service.cancelPaymentRequest(new CancelPaymentRequestCommand(7L, prId));

        var order = inOrder(paymentRequestLock, paymentRequestRepository, transactionRepository,
                cancellationFence, invoiceCancellationPort);
        order.verify(paymentRequestLock).lock(7L, prId);
        order.verify(paymentRequestRepository, times(2)).findByIdAndUserId(prId, 7L);
        order.verify(transactionRepository).findByIdAndUserId(first.getId(), 7L);
        order.verify(transactionRepository).findByUserIdAndIdempotencyKeyStartingWith(7L, "payment-request:" + prId + ":");
        order.verify(transactionRepository).findByUserIdAndExternalReference(7L, pr.getPublicId());
        order.verify(cancellationFence).fence(List.of(new PaymentExecutionId(first.getId()),
                new PaymentExecutionId(second.getId()), new PaymentExecutionId(third.getId())));
        order.verify(invoiceCancellationPort).cancel(any());
        order.verify(paymentRequestRepository).save(pr);
        assertThat(List.of(first, second, third)).allMatch(tx -> tx.getStatus() == KfeTransactionStatus.FAILED);
    }

    @Test
    void settledRelatedPaymentRejectsCancellationBeforeRemoteCall() {
        UUID prId = UUID.randomUUID();
        var pr = paymentRequest(prId, KfePaymentRequestStatus.OPEN);
        var related = baseTx(UUID.randomUUID(), KfeTransactionStatus.VALIDATING);
        when(paymentRequestRepository.findByIdAndUserId(prId, 7L)).thenReturn(Optional.of(pr));
        when(transactionRepository.findByUserIdAndIdempotencyKeyStartingWith(7L, "payment-request:" + prId + ":"))
                .thenReturn(List.of(related));
        doAnswer(invocation -> {
            related.setStatus(KfeTransactionStatus.SETTLED);
            return null;
        }).when(cancellationFence).fence(List.of(new PaymentExecutionId(related.getId())));

        assertThatThrownBy(() -> service.cancelPaymentRequest(new CancelPaymentRequestCommand(7L, prId)))
                .isInstanceOf(PaymentCancellationRejected.class);
        assertThat(pr.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
        verify(paymentRequestRepository, never()).save(any());
        verifyNoInteractions(invoiceCancellationPort, ledgerPort, liquidityPort, statementPort,
                auditLogService, dashboardPublisher);
    }

    @Test
    void unconfirmedRemoteCancellationDoesNotClosePaymentRequest() {
        assertUnconfirmedRemoteCancellation(false);
    }

    @Test
    void remoteCancellationExceptionDoesNotClosePaymentRequest() {
        assertUnconfirmedRemoteCancellation(true);
    }

    @Test
    void providerReferenceAloneStillRequiresRemoteCancellationConfirmation() {
        UUID prId = UUID.randomUUID();
        var pr = paymentRequest(prId, KfePaymentRequestStatus.OPEN);
        pr.setRail(KfeRail.LIGHTNING);
        pr.setProviderReference("provider-invoice-id");
        when(paymentRequestRepository.findByIdAndUserId(prId, 7L)).thenReturn(Optional.of(pr));

        assertThatThrownBy(() -> service.cancelPaymentRequest(new CancelPaymentRequestCommand(7L, prId)))
                .isInstanceOf(IllegalStateException.class);

        verify(invoiceCancellationPort).cancel(
                new CancelPaymentInvoiceCommand(7L, null, "provider-invoice-id", null));
        assertThat(pr.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
        verify(paymentRequestRepository, never()).save(any());
    }

    private void assertUnconfirmedRemoteCancellation(boolean providerThrows) {
        UUID prId = UUID.randomUUID();
        var pr = paymentRequest(prId, KfePaymentRequestStatus.OPEN);
        pr.setRail(KfeRail.LIGHTNING);
        pr.setPaymentHash("invoice-hash");
        when(paymentRequestRepository.findByIdAndUserId(prId, 7L)).thenReturn(Optional.of(pr));
        if (providerThrows) {
            when(invoiceCancellationPort.cancel(any()))
                    .thenThrow(new IllegalStateException("Provider unavailable"));
        }

        assertThatThrownBy(() -> service.cancelPaymentRequest(new CancelPaymentRequestCommand(7L, prId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Não foi possível confirmar o cancelamento da invoice Lightning.");
        assertThat(pr.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
        verify(paymentRequestRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
        verifyNoInteractions(ledgerPort, liquidityPort, statementPort, auditLogService, dashboardPublisher);
    }

    private TransactionalFixture transactionalFixture() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getAutoCommit()).thenReturn(true);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        var transactionManager = new DataSourceTransactionManager(dataSource);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxyFactory = new ProxyFactory(service);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(interceptor);
        return new TransactionalFixture(
                (TransactionalPaymentCancellationAdapter) proxyFactory.getProxy(), connection);
    }

    private record TransactionalFixture(TransactionalPaymentCancellationAdapter service, Connection connection) {
    }

    private KfeTransactionEntity baseTx(UUID id, KfeTransactionStatus status) {
        KfeTransactionEntity tx = new KfeTransactionEntity();
        setId(tx, id);
        tx.setUserId(7L);
        tx.setIdempotencyKey("idem-" + id);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.INBOUND);
        tx.setStatus(status);
        lenient().when(transactionRepository.findById(id)).thenReturn(Optional.of(tx));
        lenient().when(transactionRepository.findParticipantVisibleById(id, 7L, KfeRail.INTERNAL, KfeDirection.INTERNAL))
                .thenReturn(Optional.of(tx));
        lenient().when(results.findParticipantVisibleById(7L, new PaymentExecutionId(id)))
                .thenReturn(Optional.of(mock(PaymentExecutionResult.class)));
        return tx;
    }

    private static KfePaymentRequestEntity paymentRequest(UUID id, KfePaymentRequestStatus status) {
        KfePaymentRequestEntity pr = new KfePaymentRequestEntity();
        setId(pr, id);
        pr.setUserId(7L);
        pr.setStatus(status);
        pr.setPublicId("pub123");
        pr.setRail(KfeRail.ONCHAIN);
        return pr;
    }

    private static void setId(Object entity, UUID id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
