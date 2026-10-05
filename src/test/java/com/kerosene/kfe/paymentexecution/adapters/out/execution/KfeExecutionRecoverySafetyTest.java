package com.kerosene.kfe.paymentexecution.adapters.out.execution;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.bootstrap.adapters.out.observability.KfeFinancialMetrics;
import com.kerosene.kfe.ledger.adapters.in.reconciliation.KfeOnchainBalanceSyncService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.ledger.adapters.out.persistence.settlement.KfeFeeSettlementService;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import com.kerosene.kfe.liquidity.adapters.out.persistence.KfeLightningLiquidityService;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.paymentexecution.adapters.out.settlement.KfePlatformPeerInboundService;
import com.kerosene.kfe.paymentexecution.domain.exception.KfeExecutionClaimLostException;
import com.kerosene.kfe.pricing.adapters.out.bitcoin.KfeNetworkFeeEstimateService;
import com.kerosene.kfe.wallet.adapters.in.observation.KfeCustodialDepositObservationService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.ledger.adapters.out.persistence.KfeBalanceMovementRecorder;
import com.kerosene.kfe.paymentexecution.adapters.out.rail.KfePlatformOnchainDestinationRouter;
import com.kerosene.kfe.audit.KfeAuditEventLogger;
import com.kerosene.kfe.adapters.out.persistence.model.audit.*;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.*;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.*;
import com.kerosene.kfe.adapters.out.persistence.model.messaging.*;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.*;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.*;
import com.kerosene.kfe.adapters.out.persistence.model.shared.*;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.*;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;
import com.kerosene.kfe.adapters.out.persistence.repository.audit.*;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.*;
import com.kerosene.kfe.adapters.out.persistence.repository.liquidity.*;
import com.kerosene.kfe.adapters.out.persistence.repository.messaging.*;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.*;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.*;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real helper and policies, mocked persistence/financial boundaries; not a chain or ledger integration test. */
class KfeExecutionRecoverySafetyTest {
    private final KfeExecutionOutboxRepository outboxes = mock(KfeExecutionOutboxRepository.class);
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final KfeBalanceService balances = mock(KfeBalanceService.class);
    private final KfeBalanceMovementRepository movements = mock(KfeBalanceMovementRepository.class);
    private final KfeBalanceMovementRecorder recorder = mock(KfeBalanceMovementRecorder.class);
    private final KfeFeeSettlementService fees = mock(KfeFeeSettlementService.class);
    private final KfeAuditLogService audit = mock(KfeAuditLogService.class);
    private final KfeStatementService statement = mock(KfeStatementService.class);
    private final KfeIdempotencyRepository idempotency = mock(KfeIdempotencyRepository.class);
    private final KfeResponseMapper mapper = mock(KfeResponseMapper.class);
    private final KfeDashboardPublisher dashboard = mock(KfeDashboardPublisher.class);
    private final KfeHashService hash = mock(KfeHashService.class);
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoin = provider();
    private final ObjectProvider<KfeLightningLiquidityService> liquidity = provider();
    private final ObjectProvider<FinancialNotificationPort> notifications = provider();
    private final KfeFinancialMetrics metrics = mock(KfeFinancialMetrics.class);
    private final KfeAuditEventLogger auditEvents = mock(KfeAuditEventLogger.class);
    private final KfeExecutionTransactionHelper helper = new KfeExecutionTransactionHelper(outboxes, transactions,
            mock(KfeWalletRepository.class),
            mock(com.kerosene.kfe.paymentexecution.application.port.out.ExecutionSourceWalletPort.class),
            idempotency, movements, balances, audit, statement, mapper, dashboard,
            hash, new ObjectMapper(), fees, mock(KfeNetworkFeeEstimateService.class),
            KfeExecutionRecoverySafetyTest.<KfeOnchainBalanceSyncService>provider(), liquidity,
            KfeExecutionRecoverySafetyTest.<KfeCustodialDepositObservationService>provider(),
            KfeExecutionRecoverySafetyTest.<KfePlatformOnchainDestinationRouter>provider(),
            KfeExecutionRecoverySafetyTest.<KfePlatformPeerInboundService>provider(), notifications,
            bitcoin, recorder, metrics, auditEvents, 8);
    private final UUID token = UUID.randomUUID();
    private final KfeTransactionEntity tx = new KfeTransactionEntity();
    private final KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();

    @BeforeEach
    void ready() {
        tx.setUserId(42L);
        tx.setSourceWalletId(UUID.randomUUID());
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);
        tx.setStatus(KfeTransactionStatus.EXECUTING);
        tx.setBlockchainTxid("ab".repeat(32));
        tx.setIdempotencyKey("test-key");
        tx.setGrossAmountSats(10_000L);
        tx.setReceiverAmountSats(10_000L);
        tx.setTotalDebitSats(10_100L);
        tx.setNetworkFeeSats(100L);
        outbox.setTransactionId(tx.getId());
        outbox.setOperation("ONCHAIN_OUTBOUND");
        outbox.setStatus("PROCESSING");
        outbox.setClaimToken(token);
        outbox.setLeaseExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1));
        when(outboxes.findByIdForUpdate(outbox.getId())).thenReturn(Optional.of(outbox));
        when(outboxes.findByTransactionIdInForUpdate(List.of(tx.getId()))).thenReturn(List.of(outbox));
        when(transactions.findByIdForUpdate(tx.getId())).thenReturn(Optional.of(tx));
        when(mapper.buildDisplayPayload(any(), any())).thenReturn(new LinkedHashMap<>());
        when(hash.sha256(anyString())).thenReturn("test-hash");
        // Suppress asynchronous after-commit effects; this fixture does not own a real DB transaction.
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach void clearSynchronization() { TransactionSynchronizationManager.clear(); }

    enum Operation { BROADCAST, SETTLE, LIGHTNING, UNKNOWN, RETRYABLE, FINAL, RECONCILE }

    private void invoke(Operation operation, UUID transactionId, UUID claim, UUID wallet) {
        switch (operation) {
            case BROADCAST -> helper.recordOutboundBroadcast(outbox.getId(), transactionId, claim,
                    "provider", "reference", tx.getBlockchainTxid(), 100L, wallet, "{}");
            case SETTLE -> helper.settleOutbound(outbox.getId(), transactionId, claim,
                    "provider", "reference", tx.getBlockchainTxid(), 100L, wallet, "{}");
            case LIGHTNING -> helper.settleOutboundLightning(outbox.getId(), transactionId, claim,
                    "provider", "reference", null, "payment-hash", 100L, wallet, "{}");
            case UNKNOWN -> helper.markUnknown(outbox.getId(), transactionId, claim, "reference", "{}", "safe");
            case RETRYABLE -> helper.markRetryableFailure(outbox.getId(), transactionId, claim, "RETRY", "safe");
            case FINAL -> helper.markFinalFailure(outbox.getId(), transactionId, claim, "FINAL", "safe");
            case RECONCILE -> helper.markRequiresReconciliation(outbox.getId(), transactionId, claim, "REVIEW", "safe");
        }
    }

    @ParameterizedTest @EnumSource(Operation.class)
    void claimCannotWriteADifferentTransaction(Operation operation) {
        assertThatThrownBy(() -> invoke(operation, UUID.randomUUID(), token, tx.getSourceWalletId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Execution claim does not belong to the requested transaction.");
        verifyNoInteractions(transactions, audit, statement, dashboard, idempotency);
        noFinancialOrNetworkEffects();
        assertThat(outbox.getClaimToken()).isEqualTo(token);
    }

    @ParameterizedTest @EnumSource(Operation.class)
    void replacedTokenCannotWriteEvenItsOriginalTransaction(Operation operation) {
        assertThatThrownBy(() -> invoke(operation, tx.getId(), UUID.randomUUID(), tx.getSourceWalletId()))
                .isInstanceOf(KfeExecutionClaimLostException.class);
        verifyNoInteractions(transactions, audit, statement, dashboard, idempotency);
        noFinancialOrNetworkEffects();
    }

    @ParameterizedTest @EnumSource(value = Operation.class, names = {"BROADCAST", "SETTLE", "LIGHTNING"})
    void acknowledgementCannotUseAnotherSourceWallet(Operation operation) {
        assertThatThrownBy(() -> invoke(operation, tx.getId(), token, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Execution acknowledgement does not match the source wallet.");
        verify(transactions, never()).save(any());
        verifyNoInteractions(audit, statement, dashboard, idempotency);
        noFinancialOrNetworkEffects();
    }

    @ParameterizedTest @EnumSource(Operation.class)
    void workerOutcomesCannotReopenClosedOrReorgReconcilingPayments(Operation operation) {
        for (var status : List.of(KfeTransactionStatus.CANCELLED, KfeTransactionStatus.CONFLICTED_REFUNDED,
                KfeTransactionStatus.DROPPED, KfeTransactionStatus.ABANDONED, KfeTransactionStatus.REORG_RECONCILIATION)) {
            tx.setStatus(status);
            assertThatThrownBy(() -> invoke(operation, tx.getId(), token, tx.getSourceWalletId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Execution outcome cannot reopen a closed or reorg-reconciling payment.");
            assertThat(tx.getStatus()).isEqualTo(status);
            assertThat(outbox.getClaimToken()).isEqualTo(token);
        }
        verify(transactions, never()).save(any());
        verify(outboxes, never()).save(any());
        verifyNoInteractions(audit, statement, dashboard, idempotency);
        noFinancialOrNetworkEffects();
    }

    @Test void currentTokenCanAcknowledgeAfterLeaseExpiryWithoutRevivingTheLease() {
        outbox.setLeaseExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        helper.recordOutboundBroadcast(outbox.getId(), tx.getId(), token, "provider", "reference",
                tx.getBlockchainTxid(), 100L, tx.getSourceWalletId(), "{}");
        assertThat(outbox.getStatus()).isEqualTo("DISPATCHED");
        assertThat(outbox.getClaimToken()).isNull();
        assertThat(outbox.getLeaseExpiresAt()).isNull();
        noFinancialOrNetworkEffects();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void manualOutcomeCannotEraseAnActiveWorkerClaim(boolean reconciliation) {
        when(outboxes.findByTransactionId(tx.getId())).thenReturn(List.of(outbox));
        assertThatThrownBy(() -> {
            if (reconciliation) { helper.markRequiresReconciliation(null, tx.getId(), "REVIEW", "safe"); }
            else { helper.markFinalFailure(null, tx.getId(), "FINAL", "safe"); }
        }).isInstanceOf(KfeExecutionClaimLostException.class);
        assertThat(outbox.getClaimToken()).isEqualTo(token);
        verifyNoInteractions(transactions, audit, statement, idempotency);
        noFinancialOrNetworkEffects();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unresolvedObservationPreservesTheReserveAndWorkerClaimWithoutProbingInputs(boolean disappeared) {
        observe(disappeared);
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        assertThat(tx.getFailureCode()).isEqualTo(disappeared ? "TX_DISAPPEARED_INCONCLUSIVE" : "CONFLICT_OBSERVATION_UNRESOLVED");
        assertThat(outbox.getClaimToken()).isEqualTo(token);
        assertThat(outbox.getStatus()).isEqualTo("PROCESSING");
        var order = inOrder(outboxes, transactions);
        order.verify(outboxes).findByTransactionIdInForUpdate(List.of(tx.getId()));
        order.verify(transactions).findByIdForUpdate(tx.getId());
        order.verify(transactions).save(tx);
        verify(outboxes, never()).save(any());
        noFinancialOrNetworkEffects();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void alreadySettledPaymentMovesToReorgReviewWithoutReturningAnyReserve(boolean disappeared) {
        tx.setStatus(KfeTransactionStatus.SETTLED);
        tx.setConfirmations(6);
        observe(disappeared);
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.REORG_RECONCILIATION);
        assertThat(tx.getConfirmations()).isEqualTo(disappeared ? 6 : -1);
        noFinancialOrNetworkEffects();
    }

    @Test void negativeSettlementObservationDoesNotTakeTheAlreadySettledSuccessShortcut() {
        tx.setStatus(KfeTransactionStatus.SETTLED);
        assertThat(helper.settleOutboundWhenConfirmed(tx.getId(), -1)).isFalse();
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.REORG_RECONCILIATION);
        verify(outboxes).findByTransactionIdInForUpdate(List.of(tx.getId()));
        verify(transactions).findByIdForUpdate(tx.getId());
        noFinancialOrNetworkEffects();
    }

    @ParameterizedTest @EnumSource(value = KfeTransactionStatus.class,
            names = {"FAILED", "CANCELLED", "CONFLICTED_REFUNDED", "DROPPED", "ABANDONED"})
    void closedPaymentsAreNotReopenedByObservations(KfeTransactionStatus status) {
        tx.setStatus(status);
        observe(false);
        observe(true);
        assertThat(tx.getStatus()).isEqualTo(status);
        verify(transactions, never()).save(any());
        verifyNoInteractions(audit, statement, idempotency, dashboard, metrics, auditEvents);
        noFinancialOrNetworkEffects();
    }

    @ParameterizedTest @ValueSource(strings = {"rail", "direction", "txid", "blank"})
    void unrelatedObservationsCannotMutateThePayment(String mismatch) {
        String observed = tx.getBlockchainTxid();
        if (mismatch.equals("rail")) { tx.setRail(KfeRail.LIGHTNING); }
        if (mismatch.equals("direction")) { tx.setDirection(KfeDirection.INBOUND); }
        if (mismatch.equals("txid")) { observed = "cd".repeat(32); }
        if (mismatch.equals("blank")) { observed = " "; }
        helper.markOutboundConflicted(tx.getId(), observed, -1);
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
        verify(transactions, never()).save(any());
        verifyNoInteractions(audit, statement, idempotency, dashboard);
        noFinancialOrNetworkEffects();
    }

    @Test void staleDisappearanceCannotOverrideNewTransactionReference() {
        helper.markOutboundDisappeared(tx.getId(), "cd".repeat(32));
        verify(transactions, never()).save(any());
        noFinancialOrNetworkEffects();
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, Integer.MAX_VALUE})
    void nonNegativeConflictInvocationDoesNotAcquireLocks(int confirmations) {
        helper.markOutboundConflicted(tx.getId(), tx.getBlockchainTxid(), confirmations);
        verifyNoInteractions(outboxes, transactions, audit, statement, idempotency);
        noFinancialOrNetworkEffects();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void identicalObservationDoesNotRepeatEffectsOrNotifications(boolean disappeared) {
        observe(disappeared);
        int callbacks = TransactionSynchronizationManager.getSynchronizations().size();
        clearInvocations(audit, auditEvents, metrics, statement, idempotency, dashboard, transactions);
        observe(disappeared);
        verify(transactions, never()).save(any());
        verifyNoInteractions(audit, auditEvents, metrics, statement, idempotency, dashboard);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(callbacks);
        noFinancialOrNetworkEffects();
    }

    @Test void changedNegativeConfirmationsUpdateProjectionWithoutRepeatingConflictAudit() {
        observe(false);
        int callbacks = TransactionSynchronizationManager.getSynchronizations().size();
        clearInvocations(audit, auditEvents, metrics, transactions);
        helper.markOutboundConflicted(tx.getId(), tx.getBlockchainTxid(), -2);
        assertThat(tx.getConfirmations()).isEqualTo(-2);
        verify(transactions).save(tx);
        verifyNoInteractions(audit, auditEvents, metrics);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(callbacks);
        noFinancialOrNetworkEffects();
    }

    @ParameterizedTest @ValueSource(strings = {"unknown", "retry", "final", "reconcile"})
    void extremeAttemptCountCannotOverflowOrAuthorizeARefund(String outcome) {
        outbox.setAttempts(Integer.MAX_VALUE);
        switch (outcome) {
            case "unknown" -> helper.markUnknown(outbox.getId(), tx.getId(), token, "reference", null, "safe");
            case "retry" -> helper.markRetryableFailure(outbox.getId(), tx.getId(), token, "RETRY", "safe");
            case "final" -> helper.markFinalFailure(outbox.getId(), tx.getId(), token, "FINAL", "safe");
            default -> helper.markRequiresReconciliation(outbox.getId(), tx.getId(), token, "REVIEW", "safe");
        }
        assertThat(outbox.getAttempts()).isEqualTo(Integer.MAX_VALUE);
        assertThat(outbox.getStatus()).isEqualTo("UNKNOWN");
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        noFinancialOrNetworkEffects();
    }

    @Test void missingTransactionDoesNotInventFinancialEffects() {
        when(transactions.findByIdForUpdate(tx.getId())).thenReturn(Optional.empty());
        observe(false);
        observe(true);
        verify(transactions, never()).save(any());
        verifyNoInteractions(audit, statement, idempotency, dashboard);
        noFinancialOrNetworkEffects();
    }

    private void observe(boolean disappeared) {
        if (disappeared) { helper.markOutboundDisappeared(tx.getId(), tx.getBlockchainTxid()); }
        else { helper.markOutboundConflicted(tx.getId(), tx.getBlockchainTxid(), -1); }
    }

    private void noFinancialOrNetworkEffects() {
        verifyNoInteractions(balances, movements, recorder, fees, liquidity, bitcoin, notifications);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() { return mock(ObjectProvider.class); }
}
