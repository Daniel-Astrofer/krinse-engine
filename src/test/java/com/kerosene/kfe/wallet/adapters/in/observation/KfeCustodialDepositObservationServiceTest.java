package com.kerosene.kfe.wallet.adapters.in.observation;

import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.bootstrap.adapters.out.observability.KfeFinancialMetrics;
import com.kerosene.kfe.ledger.adapters.in.reconciliation.KfeOnchainBalanceSyncService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.ledger.adapters.out.persistence.settlement.KfeFeeSettlementService;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.paymentexecution.adapters.in.http.mapping.KfeResponseMapper;
import com.kerosene.kfe.pricing.adapters.in.KfePricingService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.ledger.adapters.out.persistence.KfeBalanceMovementRecorder;
import com.kerosene.kfe.bootstrap.config.bitcoin.KfeBitcoinFinalityPolicy;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.adapters.out.rail.onchain.BlockchainClient;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceMovementRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeCustodialDepositObservationServiceTest {

    private final KfeWalletRepository walletRepository = mock(KfeWalletRepository.class);
    private final KfeWalletAddressRepository addressRepository = mock(KfeWalletAddressRepository.class);
    private final KfeTransactionRepository transactionRepository = mock(KfeTransactionRepository.class);
    private final KfeBalanceMovementRepository movementRepository = mock(KfeBalanceMovementRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<BlockchainClient> blockchainClient = mock(ObjectProvider.class);
    private final KfeBalanceService balanceService = mock(KfeBalanceService.class);
    private final KfeBalanceMovementRecorder movementRecorder = mock(KfeBalanceMovementRecorder.class);
    private final KfePricingService pricingService = mock(KfePricingService.class);
    private final KfeFeeSettlementService feeSettlementService = mock(KfeFeeSettlementService.class);
    private final KfeStatementService statementService = mock(KfeStatementService.class);
    private final KfeResponseMapper responseMapper = mock(KfeResponseMapper.class);
    private final KfeDashboardPublisher dashboardPublisher = mock(KfeDashboardPublisher.class);
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<FinancialNotificationPort> notificationPort = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeOnchainBalanceSyncService> onchainBalanceSync =
            mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeMonitoredChainAddressIndex> addressIndex =
            mock(ObjectProvider.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    private KfeCustodialDepositObservationService service;

    @BeforeEach
    void setUp() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            var callback = invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class);
            return callback.doInTransaction(null);
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            Consumer<org.springframework.transaction.TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        KfeBitcoinFinalityPolicy finalityPolicy = new KfeBitcoinFinalityPolicy();
        finalityPolicy.setCreditConfirmations(3);
        finalityPolicy.setFinalityConfirmations(6);
        finalityPolicy.setReorgMonitorConfirmations(12);
        service = new KfeCustodialDepositObservationService(
                walletRepository,
                addressRepository,
                transactionRepository,
                movementRepository,
                blockchainClient,
                balanceService,
                movementRecorder,
                pricingService,
                feeSettlementService,
                statementService,
                responseMapper,
                dashboardPublisher,
                auditLogService,
                notificationPort,
                null, // KfeFinancialMetrics (null-safe)
                onchainBalanceSync,
                addressIndex,
                transactionTemplate,
                30,
                finalityPolicy,
                3,
                0L);
    }

    @Test
    void ingestZmqRawTxCreatesInboundWithoutListunspent() {
        UUID walletId = UUID.randomUUID();
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(walletId);
        wallet.setUserId(42L);
        wallet.setKind(KfeWalletKind.CUSTODIAL_ONCHAIN);
        wallet.setStatus(KfeWalletStatus.ACTIVE);

        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setAddress("tb1qcustodial");
        address.setStatus(KfeWalletAddressStatus.ACTIVE);

        KfeMonitoredChainAddressIndex index = mock(KfeMonitoredChainAddressIndex.class);
        when(addressIndex.getIfAvailable()).thenReturn(index);
        when(index.walletIdForAddress("tb1qcustodial")).thenReturn(walletId);

        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(transactionRepository.findByBlockchainTxidAndUserId("aabbccdd", 42L)).thenReturn(List.of());
        when(transactionRepository.findByIdempotencyKey("custodial-dep:" + walletId + ":aabbccdd"))
                .thenReturn(Optional.empty());
        when(pricingService.quote(any(), any(), eq(25_000L), eq(0L)))
                .thenReturn(new KfePricingService.Quote(25_000L, 25_000L, 0L, 0L));
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(responseMapper.buildDisplayPayload(any(), eq(42L))).thenReturn(java.util.Map.of());
        when(notificationPort.getIfAvailable()).thenReturn(null);

        var parsed = new KfeBitcoinZmqTxMatcher.ParsedRawTx(
                "aabbccdd",
                List.of(),
                List.of(new KfeBitcoinZmqTxMatcher.ParsedOutput("tb1qcustodial", 25_000L, 0)));

        service.ingestZmqRawTx(parsed, Set.of(walletId));

        verify(transactionRepository).save(argThat(tx ->
                tx.getDirection() == KfeDirection.INBOUND
                        && "aabbccdd".equals(tx.getBlockchainTxid())
                        && tx.getConfirmations() == 0
                        && walletId.equals(tx.getDestinationWalletId())));
        verify(statementService).recordUserStatement(eq(42L), eq(walletId), any(), any());
        verify(dashboardPublisher).publishAfterCommit(42L);
    }

    @Test
    void compensatesSettledDepositWhenCoreReportsConflict() {
        UUID walletId = UUID.randomUUID();
        KfeTransactionEntity tx = settledDeposit(walletId);
        BlockchainClient client = mock(BlockchainClient.class);
        when(blockchainClient.getIfAvailable()).thenReturn(client);
        when(walletRepository.findByKindInAndStatus(any(), eq(KfeWalletStatus.ACTIVE)))
                .thenReturn(List.of());
        when(transactionRepository.findInboundUnderReorgMonitoring(
                eq(KfeRail.ONCHAIN),
                eq(KfeDirection.INBOUND),
                any(),
                any()))
                .thenReturn(List.of(tx));
        when(client.findTransactionConfirmations(tx.getBlockchainTxid())).thenReturn(OptionalInt.of(-1));
        when(transactionRepository.findByIdForUpdate(tx.getId())).thenReturn(Optional.of(tx));
        when(movementRepository.existsByTransactionIdAndMovementTypeIn(any(), any())).thenReturn(true);
        when(movementRecorder.record(
                eq(tx.getId()), eq(walletId), eq("REVERSAL_DEBIT"), eq(1_000L), any(), any()))
                .thenReturn(true);
        when(balanceService.reverseAvailableCreditForReorg(walletId, "BTC", 1_000L))
                .thenReturn(new KfeBalanceService.ReorgDebitResult(400L, 600L, 600L));
        when(responseMapper.buildDisplayPayload(tx, tx.getUserId())).thenReturn(java.util.Map.of());

        service.reconcileCustodialDeposits();
        service.reconcileCustodialDeposits();

        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.REORG_RECONCILIATION);
        assertThat(tx.getFailureCode()).isEqualTo("DEPOSIT_REORG");
        verify(balanceService, times(1)).reverseAvailableCreditForReorg(walletId, "BTC", 1_000L);
        verify(movementRecorder).record(
                tx.getId(), walletId, "REORG_DEBT", 600L, null, "REORG_DEBT");
        verify(feeSettlementService, times(1)).reverseKeroseneFeeForReorg(tx);
        verify(auditLogService, times(1)).record(
                eq("KFE_INBOUND_REORG_COMPENSATED"),
                eq(tx.getId()),
                eq(walletId),
                any(),
                eq(KfeTransactionStatus.REORG_RECONCILIATION),
                any());
    }

    @Test
    void restoresCompensatedDepositWhenConfirmationsRecover() {
        UUID walletId = UUID.randomUUID();
        KfeTransactionEntity tx = settledDeposit(walletId);
        tx.setStatus(KfeTransactionStatus.REORG_RECONCILIATION);
        BlockchainClient client = mock(BlockchainClient.class);
        when(blockchainClient.getIfAvailable()).thenReturn(client);
        when(walletRepository.findByKindInAndStatus(any(), eq(KfeWalletStatus.ACTIVE)))
                .thenReturn(List.of());
        when(transactionRepository.findInboundUnderReorgMonitoring(any(), any(), any(), any()))
                .thenReturn(List.of(tx));
        when(client.findTransactionConfirmations(tx.getBlockchainTxid())).thenReturn(OptionalInt.of(3));
        when(transactionRepository.findByIdForUpdate(tx.getId())).thenReturn(Optional.of(tx));
        when(movementRecorder.record(
                eq(tx.getId()), eq(walletId), eq("REORG_RESTORE_CREDIT"), eq(1_000L), any(), any()))
                .thenReturn(true);
        when(responseMapper.buildDisplayPayload(tx, tx.getUserId())).thenReturn(java.util.Map.of());

        service.reconcileCustodialDeposits();

        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
        assertThat(tx.getFailureCode()).isNull();
        verify(balanceService).creditAvailable(walletId, "BTC", 1_000L);
        verify(feeSettlementService).restoreKeroseneFeeAfterReorg(tx);
    }

    private KfeTransactionEntity settledDeposit(UUID walletId) {
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(42L);
        tx.setIdempotencyKey("deposit-idempotency");
        tx.setProvider(KfeCustodialDepositObservationService.PROVIDER_CUSTODIAL_OBSERVER);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.INBOUND);
        tx.setDestinationWalletId(walletId);
        tx.setReceiverAmountSats(1_000L);
        tx.setGrossAmountSats(1_010L);
        tx.setKeroseneFeeSats(10L);
        tx.setBlockchainTxid("ab".repeat(32));
        tx.setConfirmations(3);
        tx.setStatus(KfeTransactionStatus.SETTLED);
        tx.setConfirmationMonitoringActive(true);
        return tx;
    }
}
