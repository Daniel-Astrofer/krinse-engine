package com.kerosene.kfe.wallet.adapters.in.observation;

import com.kerosene.kfe.ledger.adapters.in.reconciliation.KfeOnchainBalanceSyncService;
import com.kerosene.kfe.ledger.adapters.out.persistence.statement.KfeStatementService;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.wallet.adapters.out.bitcoin.KfeWalletDescriptorResolver;
import com.kerosene.kfe.wallet.domain.model.ChainProbeResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.bootstrap.config.bitcoin.KfeBitcoinFinalityPolicy;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.adapters.out.rail.onchain.BlockchainClient;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeColdWalletObservationServiceTest {

    private final KfeWalletRepository walletRepository = mock(KfeWalletRepository.class);
    private final KfeWalletAddressRepository addressRepository = mock(KfeWalletAddressRepository.class);
    private final KfeTransactionRepository transactionRepository = mock(KfeTransactionRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<BlockchainClient> blockchainClientProvider = mock(ObjectProvider.class);
    private final KfeStatementService statementService = mock(KfeStatementService.class);
    private final KfeDashboardPublisher dashboardPublisher = mock(KfeDashboardPublisher.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeOnchainBalanceSyncService> balanceSyncProvider = mock(ObjectProvider.class);
    private final BlockchainClient blockchainClient = mock(BlockchainClient.class);
    private final com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceRepository balanceRepository =
            mock(com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceRepository.class);
    private final KfeWalletDescriptorResolver descriptorResolver =
            mock(KfeWalletDescriptorResolver.class);
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate =
            mock(org.springframework.transaction.support.TransactionTemplate.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeMonitoredChainAddressIndex> addressIndexProvider =
            mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<com.kerosene.common.financial.notification.FinancialNotificationPort>
            notificationPortProvider = mock(ObjectProvider.class);

    private KfeColdWalletObservationService service;

    @BeforeEach
    void setUp() {
        when(blockchainClientProvider.getIfAvailable()).thenReturn(blockchainClient);
        when(balanceSyncProvider.getIfAvailable()).thenReturn(null);
        when(addressIndexProvider.getIfAvailable()).thenReturn(null);
        when(notificationPortProvider.getIfAvailable()).thenReturn(null);
        when(balanceRepository.findByWalletIds(any())).thenReturn(List.of());
        when(blockchainClient.isOutpointUnspentIncludingMempool(any(), any(Integer.class)))
                .thenReturn(true);
        // Run callbacks inline so unit tests exercise doObserveWallet logic.
        org.mockito.Mockito.doAnswer(invocation -> {
            java.util.function.Consumer<?> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        when(descriptorResolver.resolveReceiveDescriptor(any())).thenReturn(null);
        service = new KfeColdWalletObservationService(
                walletRepository,
                addressRepository,
                transactionRepository,
                blockchainClientProvider,
                statementService,
                dashboardPublisher,
                balanceSyncProvider,
                balanceRepository,
                descriptorResolver,
                addressIndexProvider,
                notificationPortProvider,
                transactionTemplate,
                20,
                finalityPolicy(),
                50);
    }

    private static KfeBitcoinFinalityPolicy finalityPolicy() {
        KfeBitcoinFinalityPolicy policy = new KfeBitcoinFinalityPolicy();
        policy.setCreditConfirmations(3);
        return policy;
    }

    @Test
    void observeWalletCreatesInboundHistoryFromScannedUtxos() {
        UUID walletId = UUID.randomUUID();
        KfeWalletEntity wallet = watchOnly(walletId, 9L);
        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setAddress("tb1qcoldreceive");

        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(addressRepository.findByWalletIdOrderByCreatedAtDesc(walletId)).thenReturn(List.of(address));
        when(blockchainClient.getUnspentOutputsMerged("tb1qcoldreceive")).thenReturn(List.of(
                new BlockchainClient.AddressUtxo(
                        "deadbeef", 0, 50_000L, "0014", 5, "tb1qcoldreceive")));
        when(blockchainClient.getUnspentOutputsFromScan(any(), any(Integer.class))).thenReturn(List.of());
        when(blockchainClient.getAddressTransactions(any())).thenReturn(null);
        when(transactionRepository.findByIdempotencyKey("cold-obs:" + walletId + ":deadbeef"))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByBlockchainTxidAndUserId("deadbeef", 9L)).thenReturn(List.of());
        when(transactionRepository.findByDestinationWalletIdAndProvider(
                        walletId, KfeColdWalletObservationService.PROVIDER_COLD_OBSERVER))
                .thenReturn(List.of());
        when(transactionRepository.findByWalletIdAndStatusIn(eq(walletId), any())).thenReturn(List.of());
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.observeWallet(walletId);

        verify(transactionRepository).save(any(KfeTransactionEntity.class));
        verify(statementService).recordUserStatement(eq(9L), eq(walletId), any(), any());
        // first observe may still use IfAbsent; cohesion upsert is covered by StatementServiceTest
        verify(dashboardPublisher).publishAfterCommit(9L);
    }

    @Test
    void observeWalletDoesNotFallbackToMempoolBlindSyncWhenDescriptorScanFails() {
        UUID walletId = UUID.randomUUID();
        KfeWalletEntity wallet = watchOnly(walletId, 9L);
        wallet.setDescriptor("wpkh([aabbccdd/84h/1h/0h]tpubExample/0/*)");
        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setAddress("tb1qcoldreceive");

        KfeOnchainBalanceSyncService sync = mock(KfeOnchainBalanceSyncService.class);
        when(balanceSyncProvider.getIfAvailable()).thenReturn(sync);
        when(descriptorResolver.resolveReceiveDescriptor(wallet))
                .thenReturn("wpkh([aabbccdd/84h/1h/0h]tpubExample/0/*)");
        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(addressRepository.findByWalletIdOrderByCreatedAtDesc(walletId)).thenReturn(List.of(address));
        // Descriptor scan fails (busy scantxoutset) — must not call syncWallet.
        when(blockchainClient.getUnspentOutputsFromScan(any(), any(Integer.class)))
                .thenThrow(new IllegalStateException("Scan already in progress"));
        when(blockchainClient.getUnspentOutputsMerged("tb1qcoldreceive")).thenReturn(List.of());
        when(transactionRepository.findByWalletIdAndStatusIn(eq(walletId), any())).thenReturn(List.of());
        when(transactionRepository.findByDestinationWalletIdAndProvider(
                        walletId, KfeColdWalletObservationService.PROVIDER_COLD_OBSERVER))
                .thenReturn(List.of());
        when(balanceRepository.findByWalletIds(any())).thenReturn(List.of());

        service.observeWallet(walletId);

        org.mockito.Mockito.verify(sync, org.mockito.Mockito.never()).syncWallet(any());
        org.mockito.Mockito.verify(sync, org.mockito.Mockito.never())
                .applyObserved(eq(walletId), any(ChainProbeResult.class));
        org.mockito.Mockito.verify(sync, org.mockito.Mockito.never())
                .applyObserved(eq(walletId), any(Long.class));
    }

    @Test
    void recordColdPsbtBroadcastCreatesExecutingOutbound() {
        UUID walletId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        when(transactionRepository.findByIdempotencyKey("cold-psbt:" + workflowId)).thenReturn(Optional.empty());
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        KfeTransactionEntity tx = service.recordColdPsbtBroadcast(
                7L,
                walletId,
                workflowId,
                "abc123",
                10_000L,
                250L,
                "tb1qdest");

        assertThat(tx.getDirection()).isEqualTo(KfeDirection.OUTBOUND);
        assertThat(tx.getRail()).isEqualTo(KfeRail.ONCHAIN);
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
        assertThat(tx.getBlockchainTxid()).isEqualTo("abc123");
        assertThat(tx.getProvider()).isEqualTo(KfeColdWalletObservationService.PROVIDER_COLD_PSBT);
        assertThat(tx.getSourceWalletId()).isEqualTo(walletId);
        assertThat(tx.getTotalDebitSats()).isEqualTo(10_250L);
        verify(statementService).recordUserStatement(eq(7L), eq(walletId), any(), any());
    }

    @Test
    void attributeWalletSpendCapsPaymentToThisWalletFunding() {
        // Our cold UTXO (222_078) is one input in a huge batch paying ~23 BTC out.
        var attr = KfeColdWalletObservationService.attributeWalletSpend(
                222_078L, 0L, 2_354_562_655L);
        assertThat(attr.paymentSats()).isEqualTo(222_078L);
        assertThat(attr.feeSats()).isZero();
        assertThat(attr.consolidationOnly()).isFalse();
    }

    @Test
    void attributeWalletSpendSplitsFeeWhenExternalSmallerThanFunding() {
        // Sent 80k external, 20k fee, no change.
        var attr = KfeColdWalletObservationService.attributeWalletSpend(
                100_000L, 0L, 80_000L);
        assertThat(attr.paymentSats()).isEqualTo(80_000L);
        assertThat(attr.feeSats()).isEqualTo(20_000L);
    }

    @Test
    void attributeWalletSpendTreatsChangeOnlyAsConsolidationFee() {
        var attr = KfeColdWalletObservationService.attributeWalletSpend(
                100_000L, 99_500L, 0L);
        assertThat(attr.paymentSats()).isZero();
        assertThat(attr.feeSats()).isEqualTo(500L);
        assertThat(attr.consolidationOnly()).isTrue();
    }

    @Test
    void attributeWalletSpendSimpleSendUsesExternalWhenWithinFunding() {
        var attr = KfeColdWalletObservationService.attributeWalletSpend(
                100_000L, 10_000L, 89_500L);
        // leftWallet = 90_000; payment capped to external 89_500; fee 500
        assertThat(attr.paymentSats()).isEqualTo(89_500L);
        assertThat(attr.feeSats()).isEqualTo(500L);
    }

    @Test
    void touchColdConfirmationsSettlesWhenMinReached() {
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(3L);
        tx.setProvider(KfeColdWalletObservationService.PROVIDER_COLD_PSBT);
        tx.setStatus(KfeTransactionStatus.EXECUTING);
        tx.setConfirmations(1);
        tx.setSourceWalletId(UUID.randomUUID());
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);
        when(transactionRepository.findByIdForUpdate(tx.getId())).thenReturn(Optional.of(tx));
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        boolean settled = service.touchColdConfirmations(tx.getId(), 4);

        assertThat(settled).isTrue();
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
        assertThat(tx.getConfirmations()).isEqualTo(4);
    }

    @Test
    void observeWalletCreatesInboundEvenWhenOutboundSharesTxid() {
        UUID walletId = UUID.randomUUID();
        KfeWalletEntity wallet = watchOnly(walletId, 9L);
        KfeWalletAddressEntity address = new KfeWalletAddressEntity();
        address.setAddress("tb1qcoldreceive");

        KfeTransactionEntity outbound = new KfeTransactionEntity();
        outbound.setUserId(9L);
        outbound.setDirection(KfeDirection.OUTBOUND);
        outbound.setBlockchainTxid("deadbeef");
        outbound.setSourceWalletId(walletId);
        outbound.setProvider(KfeColdWalletObservationService.PROVIDER_COLD_PSBT);

        when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
        when(addressRepository.findByWalletIdOrderByCreatedAtDesc(walletId)).thenReturn(List.of(address));
        when(blockchainClient.getUnspentOutputsMerged("tb1qcoldreceive")).thenReturn(List.of(
                new BlockchainClient.AddressUtxo(
                        "deadbeef", 0, 50_000L, "0014", 5, "tb1qcoldreceive")));
        when(blockchainClient.getUnspentOutputsFromScan(any(), any(Integer.class))).thenReturn(List.of());
        when(blockchainClient.getAddressTransactions(any())).thenReturn(null);
        when(transactionRepository.findByIdempotencyKey("cold-obs:" + walletId + ":deadbeef"))
                .thenReturn(Optional.empty());
        // Same chain txid already used by an outbound — must NOT block cold inbound.
        when(transactionRepository.findByBlockchainTxidAndUserId("deadbeef", 9L))
                .thenReturn(List.of(outbound));
        when(transactionRepository.findByDestinationWalletIdAndProvider(
                        walletId, KfeColdWalletObservationService.PROVIDER_COLD_OBSERVER))
                .thenReturn(List.of());
        when(transactionRepository.findByWalletIdAndStatusIn(eq(walletId), any())).thenReturn(List.of());
        when(transactionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.observeWallet(walletId);

        verify(transactionRepository).save(argThat(tx ->
                tx.getDirection() == KfeDirection.INBOUND
                        && "deadbeef".equals(tx.getBlockchainTxid())
                        && walletId.equals(tx.getDestinationWalletId())));
        verify(statementService).recordUserStatement(eq(9L), eq(walletId), any(), any());
    }

    private static KfeWalletEntity watchOnly(UUID id, long userId) {
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setId(id);
        wallet.setUserId(userId);
        wallet.setKind(KfeWalletKind.WATCH_ONLY);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        return wallet;
    }
}
