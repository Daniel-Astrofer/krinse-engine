package com.kerosene.kfe.paymentexecution.adapters.out.settlement;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.ledger.adapters.in.reconciliation.KfeOnchainBalanceSyncService;
import com.kerosene.kfe.ledger.adapters.out.observability.KfeBalanceMetrics;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.ledger.adapters.out.persistence.settlement.KfeFeeSettlementService;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeExecutionOutboxEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeIdempotencyEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeIdempotencyId;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.bootstrap.config.bitcoin.KfeBitcoinFinalityPolicy;

import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceMovementRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeIdempotencyRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.messaging.adapters.out.persistence.KfeFinancialNotificationOutboxService;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KfeInboundSettlementServiceTest {

    @Mock
    private KfeTransactionRepository transactionRepository;

    @Mock
    private KfeExecutionOutboxRepository outboxRepository;

    @Mock
    private KfeBalanceMovementRepository movementRepository;

    @Mock
    private KfeIdempotencyRepository idempotencyRepository;

    @Mock
    private KfeWalletRepository walletRepository;

    @Mock
    private KfeBalanceService balanceService;

    @Mock
    private KfeAuditLogService auditLogService;

    @Mock
    private KfeStatementService statementService;

    @Mock
    private KfeResponseMapper responseMapper;

    @Mock
    private KfeDashboardPublisher dashboardPublisher;

    @Mock
    private KfeHashService hashService;

    @Mock
    private FinancialNotificationPort notificationPort;

    @Mock
    private KfeFeeSettlementService feeSettlementService;

    @Mock
    private KfeFinancialNotificationOutboxService notificationOutbox;

    private KfeInboundSettlementService service;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.kerosene.kfe.ledger.adapters.in.reconciliation.KfeOnchainBalanceSyncService> onchainSync =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.lenient().when(onchainSync.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<com.kerosene.kfe.ledger.adapters.out.observability.KfeBalanceMetrics> metrics =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.lenient().when(metrics.getIfAvailable()).thenReturn(null);
        org.mockito.Mockito.lenient()
                .when(responseMapper.buildDisplayPayload(any(), anyLong()))
                .thenReturn(java.util.Map.of("status", "SETTLED"));
        service = new KfeInboundSettlementService(
                transactionRepository,
                outboxRepository,
                movementRepository,
                idempotencyRepository,
                walletRepository,
                balanceService,
                auditLogService,
                statementService,
                responseMapper,
                dashboardPublisher,
                hashService,
                notificationPort,
                feeSettlementService,
                onchainSync,
                metrics,
                new KfeBitcoinFinalityPolicy()
        );
        org.springframework.test.util.ReflectionTestUtils.setField(service, "notificationOutbox", notificationOutbox);
    }

    @Test
    void settleReturnsFalseIfOutboxNotFound() {
        when(outboxRepository.findByIdForUpdate(any(UUID.class))).thenReturn(Optional.empty());

        boolean result = service.settle(new KfeInboundSettlementService.InboundSettlementProof(
                UUID.randomUUID(), UUID.randomUUID(), "provider", "ref", "netRef", 100L, 1, "raw"
        ));

        assertFalse(result);
    }

    @Test
    void settleRejectsProofForAnotherTransactionBeforeLoadingFinancialState() {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        UUID outboxId = outbox.getId();
        UUID actualTransactionId = UUID.randomUUID();
        bindOutbox(outbox, actualTransactionId, "ONCHAIN_INBOUND");
        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));

        boolean result = service.settle(new KfeInboundSettlementService.InboundSettlementProof(
                UUID.randomUUID(), outboxId, "provider", "ref", txid(), 100L, 3, "raw"));

        assertFalse(result);
        verify(transactionRepository, never()).findByIdForUpdate(any(UUID.class));
        verify(transactionRepository, never()).acquireInboundProviderReferenceLock(anyString());
        verify(balanceService, never()).creditAvailable(any(), anyString(), anyLong());
        verify(movementRepository, never()).save(any());
        verify(outboxRepository, never()).save(any(KfeExecutionOutboxEntity.class));
    }

    @Test
    void settleFailsOutboxIfTransactionNotFound() {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        UUID outboxId = outbox.getId();
        UUID txId = UUID.randomUUID();
        bindOutbox(outbox, txId, "ONCHAIN_INBOUND");

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(any(UUID.class))).thenReturn(Optional.empty());

        boolean result = service.settle(new KfeInboundSettlementService.InboundSettlementProof(
                txId, outboxId, "provider", "ref", txid(), 100L, 3, "raw"
        ));

        assertFalse(result);
        verify(outboxRepository).save(outbox);
        assertTrue(outbox.getStatus().equals("FAILED_FINAL"));
    }

    @Test
    void settleReturnsTrueIfTransactionAlreadySettled() {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        UUID outboxId = outbox.getId();

        KfeTransactionEntity tx = new KfeTransactionEntity();
        UUID txId = tx.getId();
        tx.setStatus(KfeTransactionStatus.SETTLED);
        tx.setUserId(99L);
        tx.setDestinationWalletId(UUID.randomUUID());
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection.INBOUND);
        tx.setGrossAmountSats(100L);
        tx.setReceiverAmountSats(100L);
        tx.setProviderReference("ref");
        tx.setBlockchainTxid(txid());
        bindOutbox(outbox, txId, "ONCHAIN_INBOUND");

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.of(tx));
        when(walletRepository.findByIdAndUserIdForUpdate(tx.getDestinationWalletId(), 99L))
                .thenReturn(Optional.of(wallet(tx.getDestinationWalletId(), 99L)));

        boolean result = service.settle(new KfeInboundSettlementService.InboundSettlementProof(
                txId, outboxId, "provider", "ref", txid(), 100L, 3, "raw"
        ));

        assertTrue(result);
        verify(outboxRepository).save(outbox);
        assertTrue(outbox.getStatus().equals("DISPATCHED"));
    }

    @Test
    void settleRejectsDestinationOwnedByAnotherUserWithoutFinancialEffects() {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        UUID outboxId = outbox.getId();
        KfeTransactionEntity tx = new KfeTransactionEntity();
        UUID walletId = UUID.randomUUID();
        UUID txId = tx.getId();
        tx.setStatus(KfeTransactionStatus.EXECUTING);
        tx.setUserId(99L);
        tx.setDestinationWalletId(walletId);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection.INBOUND);
        tx.setGrossAmountSats(100L);
        tx.setReceiverAmountSats(100L);
        bindOutbox(outbox, txId, "ONCHAIN_INBOUND");
        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.of(tx));
        when(walletRepository.findByIdAndUserIdForUpdate(walletId, 99L)).thenReturn(Optional.empty());

        boolean result = service.settle(new KfeInboundSettlementService.InboundSettlementProof(
                txId, outboxId, "provider", "ref", txid(), 100L, 3, "raw"));

        assertFalse(result);
        assertTrue(outbox.getStatus().equals("UNKNOWN"));
        verify(balanceService, never()).creditAvailable(any(), anyString(), anyLong());
        verify(movementRepository, never()).save(any());
        verify(transactionRepository, never()).save(tx);
        verify(outboxRepository, never()).save(outbox);
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(), anyMap());
    }

    @Test
    void settleReturnsFalseIfObservedAmountLessThanGrossAmount() {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        UUID outboxId = outbox.getId();

        KfeTransactionEntity tx = new KfeTransactionEntity();
        UUID txId = tx.getId();
        tx.setStatus(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        tx.setDestinationWalletId(UUID.randomUUID());
        tx.setGrossAmountSats(1000L);
        tx.setUserId(99L);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection.INBOUND);
        bindOutbox(outbox, txId, "ONCHAIN_INBOUND");

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.of(tx));
        when(walletRepository.findByIdAndUserIdForUpdate(tx.getDestinationWalletId(), 99L))
                .thenReturn(Optional.of(wallet(tx.getDestinationWalletId(), 99L)));

        boolean result = service.settle(new KfeInboundSettlementService.InboundSettlementProof(
                txId, outboxId, "provider", "ref", txid(), 900L, 3, "raw"
        ));

        assertFalse(result);
        verify(transactionRepository).save(tx);
        assertTrue(tx.getFailureCode().equals("INBOUND_AMOUNT_BELOW_EXPECTED"));
    }

    @Test
    void settleCompletesSuccessfullyForOnchain() {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        UUID outboxId = outbox.getId();

        KfeTransactionEntity tx = new KfeTransactionEntity();
        UUID txId = tx.getId();
        UUID destWalletId = UUID.randomUUID();
        tx.setUserId(99L);
        tx.setStatus(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        tx.setDestinationWalletId(destWalletId);
        tx.setGrossAmountSats(1000L);
        tx.setReceiverAmountSats(1000L);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection.INBOUND);

        tx.setIdempotencyKey("idem123");
        bindOutbox(outbox, txId, "ONCHAIN_INBOUND");
        when(walletRepository.findByIdAndUserIdForUpdate(destWalletId, 99L))
                .thenReturn(Optional.of(wallet(destWalletId, 99L)));

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.of(tx));
        when(hashService.sha256(anyString())).thenReturn("hashed");
        UUID notificationEventId = UUID.randomUUID();
        when(notificationOutbox.stableEventId("DEPOSIT_CONFIRMED", txId, "settled"))
                .thenReturn(notificationEventId);

        KfeIdempotencyEntity idemEntity = new KfeIdempotencyEntity();
        when(idempotencyRepository.findById(any(KfeIdempotencyId.class))).thenReturn(Optional.of(idemEntity));

        boolean result = service.settle(new KfeInboundSettlementService.InboundSettlementProof(
                txId, outboxId, "provider", txid(), txid(), 1000L, 3, "raw"
        ));

        assertTrue(result);

        verify(balanceService).creditAvailable(destWalletId, "BTC", 1000L);
        verify(transactionRepository).save(tx);
        verify(movementRepository).save(any());
        verify(auditLogService).record(anyString(), any(), any(), any(), any(), anyMap());
        verify(statementService).recordUserStatement(anyLong(), any(), any(), anyMap());
        verify(outboxRepository).save(outbox);
        verify(dashboardPublisher).publishAfterCommit(99L);
        verify(notificationOutbox).enqueue(
                eq(notificationEventId), eq(99L), eq(txId), eq("DEPOSIT_CONFIRMED"), anyMap());

        assertTrue(tx.getStatus() == KfeTransactionStatus.SETTLED);
        org.junit.jupiter.api.Assertions.assertEquals(txid(), tx.getBlockchainTxid());
    }

    @Test
    void settleCompletesSuccessfullyForLightningAndPersistsNormalizedReferences() {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        UUID outboxId = outbox.getId();
        KfeTransactionEntity tx = new KfeTransactionEntity();
        UUID txId = tx.getId();
        UUID walletId = UUID.randomUUID();
        String paymentHash = "payment-hash";
        tx.setUserId(99L);
        tx.setStatus(KfeTransactionStatus.EXECUTING);
        tx.setDestinationWalletId(walletId);
        tx.setGrossAmountSats(500L);
        tx.setReceiverAmountSats(500L);
        tx.setRail(KfeRail.LIGHTNING);
        tx.setDirection(com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection.INBOUND);
        bindOutbox(outbox, txId, "LIGHTNING_INBOUND");
        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.of(tx));
        when(walletRepository.findByIdAndUserIdForUpdate(walletId, 99L))
                .thenReturn(Optional.of(wallet(walletId, 99L)));
        when(hashService.sha256(anyString())).thenReturn("hashed");
        UUID notificationEventId = UUID.randomUUID();
        when(notificationOutbox.stableEventId("DEPOSIT_CONFIRMED", txId, "settled"))
                .thenReturn(notificationEventId);

        boolean result = service.settle(new KfeInboundSettlementService.InboundSettlementProof(
                txId, outboxId, "gateway", "  invoice-ref  ", "  " + paymentHash + "  ",
                500L, 1, "raw"));

        assertTrue(result);
        assertTrue(tx.getStatus() == KfeTransactionStatus.SETTLED);
        org.junit.jupiter.api.Assertions.assertEquals("invoice-ref", tx.getProviderReference());
        org.junit.jupiter.api.Assertions.assertEquals(paymentHash, tx.getPaymentHash());
        org.junit.jupiter.api.Assertions.assertEquals("invoice-ref", outbox.getProviderReference());
        verify(balanceService).creditAvailable(walletId, "BTC", 500L);
        verify(movementRepository).save(any());
        verify(outboxRepository).save(outbox);
        verify(notificationOutbox).enqueue(
                eq(notificationEventId), eq(99L), eq(txId), eq("DEPOSIT_CONFIRMED"), anyMap());
    }

    @Test
    void settledReplayWithSameReferencesDoesNotCreditAgain() {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        UUID outboxId = outbox.getId();
        KfeTransactionEntity tx = new KfeTransactionEntity();
        UUID txId = tx.getId();
        UUID walletId = UUID.randomUUID();
        tx.setUserId(99L);
        tx.setStatus(KfeTransactionStatus.SETTLED);
        tx.setDestinationWalletId(walletId);
        tx.setGrossAmountSats(100L);
        tx.setReceiverAmountSats(100L);
        tx.setRail(KfeRail.LIGHTNING);
        tx.setDirection(com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection.INBOUND);
        tx.setProviderReference("invoice-ref");
        tx.setPaymentHash("payment-hash");
        bindOutbox(outbox, txId, "LIGHTNING_INBOUND");
        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.of(tx));
        when(walletRepository.findByIdAndUserIdForUpdate(walletId, 99L))
                .thenReturn(Optional.of(wallet(walletId, 99L)));

        boolean result = service.settle(new KfeInboundSettlementService.InboundSettlementProof(
                txId, outboxId, "gateway", "invoice-ref", "payment-hash", 100L, 1, "raw"));

        assertTrue(result);
        verify(balanceService, never()).creditAvailable(any(), anyString(), anyLong());
        verify(movementRepository, never()).save(any());
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(), anyMap());
        verify(outboxRepository).save(outbox);
        assertTrue(outbox.getStatus().equals("DISPATCHED"));
    }

    @Test
    void settleDoesNotCreditAgainWhenProviderReferenceAlreadySettled() {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        UUID outboxId = outbox.getId();

        KfeTransactionEntity tx = new KfeTransactionEntity();
        UUID txId = tx.getId();
        tx.setStatus(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        tx.setDestinationWalletId(UUID.randomUUID());
        tx.setGrossAmountSats(1000L);
        tx.setReceiverAmountSats(1000L);
        tx.setUserId(99L);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection.INBOUND);
        bindOutbox(outbox, txId, "ONCHAIN_INBOUND");

        KfeTransactionEntity settled = new KfeTransactionEntity();
        settled.setStatus(KfeTransactionStatus.SETTLED);
        settled.setProvider("provider");
        settled.setProviderReference("ref");

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.of(tx));
        when(walletRepository.findByIdAndUserIdForUpdate(tx.getDestinationWalletId(), 99L))
                .thenReturn(Optional.of(wallet(tx.getDestinationWalletId(), 99L)));
        when(transactionRepository.findByProviderReferenceAndStatusForUpdate(
                "ref",
                KfeTransactionStatus.SETTLED)).thenReturn(java.util.List.of(settled));

        boolean result = service.settle(new KfeInboundSettlementService.InboundSettlementProof(
                txId, outboxId, "provider", "ref", txid(), 1000L, 3, "raw"
        ));

        assertFalse(result);
        assertTrue(outbox.getStatus().equals("UNKNOWN"));
        verify(outboxRepository).save(outbox);
        verify(balanceService, never()).creditAvailable(any(), anyString(), anyLong());
        verify(movementRepository, never()).save(any());
        verify(transactionRepository).save(tx);
        org.junit.jupiter.api.Assertions.assertEquals("INBOUND_PROVIDER_REFERENCE_CONFLICT", tx.getFailureCode());
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(), anyMap());
        verify(statementService, never()).recordUserStatement(anyLong(), any(), any(), anyMap());
        verify(idempotencyRepository, never()).findById(any());
        verify(dashboardPublisher, never()).publishAfterCommit(anyLong());
    }

    @Test
    void normalizedProviderReferenceStillDetectsConflictWithAnotherSettledTransaction() {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        UUID outboxId = outbox.getId();
        KfeTransactionEntity tx = new KfeTransactionEntity();
        UUID txId = tx.getId();
        tx.setStatus(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        tx.setDestinationWalletId(UUID.randomUUID());
        tx.setGrossAmountSats(1000L);
        tx.setReceiverAmountSats(1000L);
        tx.setUserId(99L);
        tx.setRail(KfeRail.LIGHTNING);
        tx.setDirection(com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection.INBOUND);
        bindOutbox(outbox, txId, "LIGHTNING_INBOUND");

        KfeTransactionEntity settled = new KfeTransactionEntity();
        settled.setStatus(KfeTransactionStatus.SETTLED);
        settled.setProviderReference("ref");

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.of(tx));
        when(walletRepository.findByIdAndUserIdForUpdate(tx.getDestinationWalletId(), 99L))
                .thenReturn(Optional.of(wallet(tx.getDestinationWalletId(), 99L)));
        when(transactionRepository.findByProviderReferenceAndStatusForUpdate(
                "ref", KfeTransactionStatus.SETTLED)).thenReturn(java.util.List.of(settled));

        boolean result = service.settle(new KfeInboundSettlementService.InboundSettlementProof(
                txId, outboxId, "gateway", "  ref  ", "payment-hash", 1000L, 1, "raw"));

        assertFalse(result);
        assertTrue(outbox.getStatus().equals("UNKNOWN"));
        assertTrue(tx.getFailureCode().equals("INBOUND_PROVIDER_REFERENCE_CONFLICT"));
        verify(balanceService, never()).creditAvailable(any(), anyString(), anyLong());
        verify(movementRepository, never()).save(any());
        verify(notificationOutbox, never()).enqueue(any(), any(), any(), anyString(), anyMap());
    }

    private static void bindOutbox(KfeExecutionOutboxEntity outbox, UUID transactionId, String operation) {
        outbox.setTransactionId(transactionId);
        outbox.setOperation(operation);
        outbox.setStatus("UNKNOWN");
    }

    private static KfeWalletEntity wallet(UUID id, long userId) {
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(id);
        wallet.setUserId(userId);
        return wallet;
    }

    private static String txid() {
        return "a".repeat(64);
    }

}
