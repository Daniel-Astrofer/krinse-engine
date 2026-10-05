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
import com.kerosene.kfe.pricing.adapters.out.bitcoin.KfeNetworkFeeEstimateService;
import com.kerosene.kfe.wallet.adapters.in.observation.KfeCustodialDepositObservationService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.audit.KfeAuditEventLogger;
import com.kerosene.kfe.ledger.adapters.out.persistence.KfeBalanceMovementRecorder;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceMovementEntity;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeExecutionOutboxEntity;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceMovementRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeIdempotencyRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KfeExecutionTransactionHelperTest {

    private final KfeExecutionOutboxRepository outboxRepository = mock(KfeExecutionOutboxRepository.class);
    private final KfeTransactionRepository transactionRepository = mock(KfeTransactionRepository.class);
    private final KfeWalletRepository walletRepository = mock(KfeWalletRepository.class);
    private final KfeIdempotencyRepository idempotencyRepository = mock(KfeIdempotencyRepository.class);
    private final KfeBalanceMovementRepository movementRepository = mock(KfeBalanceMovementRepository.class);
    private final KfeBalanceService balanceService = mock(KfeBalanceService.class);
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);
    private final KfeStatementService statementService = mock(KfeStatementService.class);
    private final KfeResponseMapper responseMapper = mock(KfeResponseMapper.class);
    private final KfeDashboardPublisher dashboardPublisher = mock(KfeDashboardPublisher.class);
    private final KfeHashService hashService = mock(KfeHashService.class);
    private final KfeFeeSettlementService feeSettlementService = mock(KfeFeeSettlementService.class);
    private final KfeNetworkFeeEstimateService networkFeeEstimateService = mock(KfeNetworkFeeEstimateService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSyncProvider =
            mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeLightningLiquidityService> lightningLiquidityProvider =
            mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeCustodialDepositObservationService> custodialDepositProvider =
            mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<com.kerosene.kfe.paymentexecution.adapters.out.rail.KfePlatformOnchainDestinationRouter>
            platformRouterProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfePlatformPeerInboundService> peerInboundProvider =
            mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<FinancialNotificationPort> notificationPortProvider =
            mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient> bitcoinCoreRpcProvider =
            mock(ObjectProvider.class);
    private final KfeBalanceMovementRecorder movementRecorder = mock(KfeBalanceMovementRecorder.class);

    private final KfeExecutionTransactionHelper helper = helper(8);

    @Test
    void confirmationSettlementLocksAllOutboxesBeforeTransaction() {
        UUID walletId = UUID.randomUUID();
        KfeTransactionEntity tx = executingTransaction(walletId, 500L);
        tx.setBlockchainTxid("confirmed-txid");
        KfeExecutionOutboxEntity outbox = claimedOutbox(tx.getId());
        KfeExecutionOutboxEntity otherOutbox = claimedOutbox(tx.getId());
        when(outboxRepository.findByTransactionIdInForUpdate(List.of(tx.getId())))
                .thenReturn(List.of(outbox, otherOutbox));
        when(transactionRepository.findByIdForUpdate(tx.getId())).thenReturn(Optional.of(tx));
        when(hashService.sha256(anyString())).thenReturn("hash");

        assertThat(helper.settleOutboundWhenConfirmed(tx.getId(), 6)).isTrue();

        var locks = inOrder(outboxRepository, transactionRepository, balanceService);
        locks.verify(outboxRepository).findByTransactionIdInForUpdate(List.of(tx.getId()));
        locks.verify(transactionRepository).findByIdForUpdate(tx.getId());
        locks.verify(balanceService).settleReservedDebit(walletId, "BTC", tx.getTotalDebitSats());
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
        assertThat(outbox.getStatus()).isEqualTo("DISPATCHED");
        verify(outboxRepository, never()).findByIdForUpdate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void confirmationSettlementDoesNotDebitCancelledTransaction() {
        KfeTransactionEntity tx = executingTransaction(UUID.randomUUID(), 500L);
        tx.setStatus(KfeTransactionStatus.FAILED);
        tx.setFailureCode("USER_CANCELLED");
        when(transactionRepository.findByIdForUpdate(tx.getId())).thenReturn(Optional.of(tx));

        assertThat(helper.settleOutboundWhenConfirmed(tx.getId(), 6)).isFalse();

        var locks = inOrder(outboxRepository, transactionRepository);
        locks.verify(outboxRepository).findByTransactionIdInForUpdate(List.of(tx.getId()));
        locks.verify(transactionRepository).findByIdForUpdate(tx.getId());
        verifyNoTerminalSideEffects(tx);
    }

    @Test
    void settleOutboundOnlyDispatchesOutboxWhenTransactionAlreadySettled() {
        UUID outboxId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        KfeExecutionOutboxEntity outbox = claimedOutbox(transactionId);
        KfeTransactionEntity tx = mock(KfeTransactionEntity.class);
        when(tx.getStatus()).thenReturn(KfeTransactionStatus.SETTLED);
        when(tx.getProviderReference()).thenReturn("existing-provider-ref");

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(transactionId)).thenReturn(Optional.of(tx));

        helper.settleOutbound(
                outboxId, transactionId, outbox.getClaimToken(), "provider",
                "new-provider-ref", "txid", 12L, walletId, "{}");

        assertThat(outbox.getStatus()).isEqualTo("DISPATCHED");
        assertThat(outbox.getProviderReference()).isEqualTo("new-provider-ref");
        assertThat(outbox.getDispatchedAt()).isNotNull();
        assertThat(outbox.getClaimedBy()).isNull();
        assertThat(outbox.getClaimedAt()).isNull();
        verifyNoTerminalSideEffects(tx);
        verify(outboxRepository).save(outbox);
    }

    @Test
    void markFinalFailureOnlyFinalizesOutboxWhenTransactionAlreadyFailed() {
        UUID outboxId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        KfeExecutionOutboxEntity outbox = claimedOutbox(transactionId);
        outbox.setAttempts(3);
        KfeTransactionEntity tx = mock(KfeTransactionEntity.class);
        when(tx.getStatus()).thenReturn(KfeTransactionStatus.FAILED);
        when(tx.getFailureCode()).thenReturn("PROVIDER_FINAL_FAILURE");
        when(tx.getFailureMessage()).thenReturn("provider rejected payment");

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(transactionId)).thenReturn(Optional.of(tx));

        helper.markFinalFailure(
                outboxId, transactionId, outbox.getClaimToken(), "NEW_FAILURE", "should not replay");

        assertThat(outbox.getStatus()).isEqualTo("FAILED_FINAL");
        assertThat(outbox.getAttempts()).isEqualTo(3);
        assertThat(outbox.getLastError()).isEqualTo("PROVIDER_FINAL_FAILURE: provider rejected payment");
        assertThat(outbox.getNextAttemptAt()).isNull();
        assertThat(outbox.getClaimedBy()).isNull();
        assertThat(outbox.getClaimedAt()).isNull();
        verifyNoTerminalSideEffects(tx);
        verify(outboxRepository).save(outbox);
    }

    @Test
    void duplicateFinalFailureDoesNotReleaseReserveAgain() {
        UUID outboxId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        KfeExecutionOutboxEntity outbox = claimedOutbox(transactionId);
        KfeTransactionEntity tx = mock(KfeTransactionEntity.class);
        when(tx.getStatus()).thenReturn(KfeTransactionStatus.FAILED);
        when(tx.getFailureCode()).thenReturn("PROVIDER_FINAL_FAILURE");
        when(tx.getFailureMessage()).thenReturn("provider rejected payment");

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(transactionId)).thenReturn(Optional.of(tx));

        helper.markFinalFailure(
                outboxId, transactionId, outbox.getClaimToken(),
                "PROVIDER_FINAL_FAILURE", "provider rejected payment");

        assertThat(outbox.getStatus()).isEqualTo("FAILED_FINAL");
        assertThat(outbox.getLastError()).isEqualTo("PROVIDER_FINAL_FAILURE: provider rejected payment");
        verify(balanceService, never()).releaseReserved(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(movementRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(transactionRepository, never()).save(tx);
        verify(outboxRepository).save(outbox);
    }

    @Test
    void settleOutboundDebitsActualFeeAndReleasesUnusedFeeReserve() {
        UUID outboxId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        KfeExecutionOutboxEntity outbox = claimedOutbox(transactionId);
        KfeTransactionEntity tx = executingTransaction(walletId, 1_000L);

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(transactionId)).thenReturn(Optional.of(tx));
        when(hashService.sha256(anyString())).thenReturn("hash");

        helper.settleOutbound(
                outboxId,
                transactionId,
                outbox.getClaimToken(),
                "BITCOIN_CORE",
                "provider-reference",
                "blockchain-txid",
                700L,
                walletId,
                "{}");

        assertThat(tx.getNetworkFeeSats()).isEqualTo(700L);
        assertThat(tx.getTotalDebitSats()).isEqualTo(101_600L);
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
        assertThat(tx.getProvider()).isEqualTo("BITCOIN_CORE");
        assertThat(outbox.getStatus()).isEqualTo("DISPATCHED");
        verify(balanceService).releaseReserved(walletId, "BTC", 300L);
        verify(balanceService).settleReservedDebit(walletId, "BTC", 101_600L);

        ArgumentCaptor<KfeBalanceMovementEntity> movements =
                ArgumentCaptor.forClass(KfeBalanceMovementEntity.class);
        verify(movementRepository, times(2)).save(movements.capture());
        assertThat(movements.getAllValues())
                .extracting(KfeBalanceMovementEntity::getMovementType, KfeBalanceMovementEntity::getAmountSats)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("RELEASE_FEE_RESERVE", 300L),
                        org.assertj.core.groups.Tuple.tuple("SETTLE_DEBIT", 101_600L));
        verify(feeSettlementService).creditKeroseneFee(tx);
    }

    @Test
    void settleOutboundKeepsReserveLockedWhenActualFeeExceedsReservedFee() {
        UUID outboxId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        KfeExecutionOutboxEntity outbox = claimedOutbox(transactionId);
        KfeTransactionEntity tx = executingTransaction(walletId, 500L);

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(transactionId)).thenReturn(Optional.of(tx));
        when(hashService.sha256(anyString())).thenReturn("hash");

        helper.settleOutbound(
                outboxId,
                transactionId,
                outbox.getClaimToken(),
                "BITCOIN_CORE",
                "provider-reference",
                "blockchain-txid",
                600L,
                walletId,
                "{}");

        assertThat(tx.getNetworkFeeSats()).isEqualTo(500L);
        assertThat(tx.getTotalDebitSats()).isEqualTo(101_400L);
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        assertThat(tx.getFailureCode()).isEqualTo("ACTUAL_FEE_EXCEEDS_RESERVED");
        assertThat(outbox.getStatus()).isEqualTo("UNKNOWN");
        assertThat(outbox.getProviderReference()).isEqualTo("provider-reference");
        verifyNoInteractions(balanceService, feeSettlementService);
        verify(movementRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void retryExhaustionFailsTransactionAndReleasesReservedBalance() {
        UUID outboxId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        KfeExecutionOutboxEntity outbox = claimedOutbox(transactionId);
        outbox.setAttempts(2);
        KfeTransactionEntity tx = executingTransaction(walletId, 500L);

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(transactionId)).thenReturn(Optional.of(tx));
        when(hashService.sha256(anyString())).thenReturn("hash");

        helper(3).markRetryableFailure(
                outboxId,
                transactionId,
                outbox.getClaimToken(),
                "PROVIDER_RETRYABLE_FAILURE",
                "Bitcoin provider unavailable");

        assertThat(outbox.getAttempts()).isEqualTo(3);
        assertThat(outbox.getStatus()).isEqualTo("FAILED_FINAL");
        assertThat(outbox.getNextAttemptAt()).isNull();
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.FAILED);
        assertThat(tx.getFailureCode()).isEqualTo("PROVIDER_RETRY_EXHAUSTED");
        verify(balanceService).releaseReserved(walletId, "BTC", tx.getTotalDebitSats());
    }

    @Test
    void retryBelowLimitRemainsScheduledWithoutReleasingReserve() {
        UUID outboxId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        KfeExecutionOutboxEntity outbox = claimedOutbox(transactionId);
        outbox.setAttempts(1);
        KfeTransactionEntity tx = executingTransaction(walletId, 500L);

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(transactionId)).thenReturn(Optional.of(tx));
        when(hashService.sha256(anyString())).thenReturn("hash");

        helper(3).markRetryableFailure(
                outboxId,
                transactionId,
                outbox.getClaimToken(),
                "PROVIDER_RETRYABLE_FAILURE",
                "Bitcoin provider unavailable");

        assertThat(outbox.getAttempts()).isEqualTo(2);
        assertThat(outbox.getStatus()).isEqualTo("FAILED_RETRYABLE");
        assertThat(outbox.getNextAttemptAt()).isNotNull();
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
        verifyNoInteractions(balanceService);
    }

    private KfeExecutionTransactionHelper helper(int maxRetryAttempts) {
        when(onchainBalanceSyncProvider.getIfAvailable()).thenReturn(null);
        when(lightningLiquidityProvider.getIfAvailable()).thenReturn(null);
        when(custodialDepositProvider.getIfAvailable()).thenReturn(null);
        when(platformRouterProvider.getIfAvailable()).thenReturn(null);
        when(peerInboundProvider.getIfAvailable()).thenReturn(null);
        when(responseMapper.buildDisplayPayload(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new java.util.LinkedHashMap<>());
        return new KfeExecutionTransactionHelper(
                outboxRepository,
                transactionRepository,
                walletRepository,
                mock(com.kerosene.kfe.paymentexecution.application.port.out.ExecutionSourceWalletPort.class),
                idempotencyRepository,
                movementRepository,
                balanceService,
                auditLogService,
                statementService,
                responseMapper,
                dashboardPublisher,
                hashService,
                new ObjectMapper(),
                feeSettlementService,
                networkFeeEstimateService,
                onchainBalanceSyncProvider,
                lightningLiquidityProvider,
                custodialDepositProvider,
                platformRouterProvider,
                peerInboundProvider,
                notificationPortProvider,
                bitcoinCoreRpcProvider,
                movementRecorder,
                mock(KfeFinancialMetrics.class),
                mock(KfeAuditEventLogger.class),
                maxRetryAttempts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ciphertext", "preparedHash", "executionReference", "providerReference", "txid", "paymentHash"})
    void finalFailureCannotReleaseReserveWhenExternalExecutionIsNotProvenAbsent(String evidence) {
        var tx = executingTransaction(UUID.randomUUID(), 500L);
        var outbox = claimedOutbox(tx.getId());
        switch (evidence) {
            case "ciphertext" -> outbox.setPreparedPayloadCiphertext("test-encrypted");
            case "preparedHash" -> outbox.setPreparedPayloadHash("test-hash");
            case "executionReference" -> outbox.setExecutionReference("test-reference");
            case "providerReference" -> tx.setProviderReference("test-provider-reference");
            case "txid" -> tx.setBlockchainTxid("test-txid");
            case "paymentHash" -> tx.setPaymentHash("test-payment-hash");
        }
        when(outboxRepository.findByIdForUpdate(outbox.getId())).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(tx.getId())).thenReturn(Optional.of(tx));
        when(hashService.sha256(anyString())).thenReturn("hash");
        helper.markFinalFailure(outbox.getId(), tx.getId(), outbox.getClaimToken(), "INVALID_QUOTE", "SECRET-LOCAL-ERROR");
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        assertThat(tx.getFailureCode()).isEqualTo("EXTERNAL_EXECUTION_NOT_PROVEN_ABSENT");
        assertThat(outbox.getStatus()).isEqualTo("UNKNOWN");
        assertThat(outbox.getNextAttemptAt()).isAfter(LocalDateTime.now(java.time.ZoneOffset.UTC));
        assertThat(outbox.getClaimToken()).isNull();
        assertThat(outbox.getLastError()).doesNotContain("SECRET-LOCAL-ERROR");
        verifyNoInteractions(balanceService, feeSettlementService, movementRepository, movementRecorder, lightningLiquidityProvider);
    }

    @Test void exhaustedRetryAfterPreparedSendRequiresReconciliationWithoutRefund() {
        var tx = executingTransaction(UUID.randomUUID(), 500L);
        var outbox = claimedOutbox(tx.getId());
        outbox.setPreparedPayloadCiphertext("test-encrypted");
        outbox.setAttempts(2);
        when(outboxRepository.findByIdForUpdate(outbox.getId())).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(tx.getId())).thenReturn(Optional.of(tx));
        when(hashService.sha256(anyString())).thenReturn("hash");
        helper(3).markRetryableFailure(outbox.getId(), tx.getId(), outbox.getClaimToken(), "RETRYABLE", "test-error");
        assertThat(outbox.getAttempts()).isEqualTo(3);
        assertThat(outbox.getStatus()).isEqualTo("UNKNOWN");
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        verifyNoInteractions(balanceService, feeSettlementService, movementRepository, movementRecorder);
    }

    private KfeExecutionOutboxEntity claimedOutbox(UUID transactionId) {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        outbox.setTransactionId(transactionId);
        outbox.setOperation("ONCHAIN_OUTBOUND");
        outbox.setStatus("PROCESSING");
        outbox.setClaimedBy("worker");
        outbox.setClaimedAt(LocalDateTime.now());
        outbox.setClaimToken(UUID.randomUUID());
        outbox.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(10));
        outbox.setPayloadHash("payload-hash");
        return outbox;
    }

    private KfeTransactionEntity executingTransaction(UUID walletId, long reservedFeeSats) {
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(42L);
        tx.setIdempotencyKey("idempotency-key");
        tx.setSourceWalletId(walletId);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);
        tx.setStatus(KfeTransactionStatus.EXECUTING);
        tx.setGrossAmountSats(100_000L);
        tx.setReceiverAmountSats(100_000L);
        tx.setNetworkFeeSats(reservedFeeSats);
        tx.setKeroseneFeeSats(900L);
        tx.setTotalDebitSats(100_900L + reservedFeeSats);
        tx.setExternalReference("bcrt1qdestination");
        tx.setMemo("memo");
        return tx;
    }

    private void verifyNoTerminalSideEffects(KfeTransactionEntity tx) {
        verifyNoInteractions(
                balanceService,
                movementRepository,
                statementService,
                idempotencyRepository,
                auditLogService,
                dashboardPublisher,
                feeSettlementService);
        verify(transactionRepository, never()).save(tx);
    }
}
