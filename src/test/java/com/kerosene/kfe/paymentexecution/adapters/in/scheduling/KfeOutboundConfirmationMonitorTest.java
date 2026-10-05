package com.kerosene.kfe.paymentexecution.adapters.in.scheduling;

import com.kerosene.kfe.bootstrap.adapters.out.observability.KfeFinancialMetrics;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.KfeExecutionTransactionHelper;
import com.kerosene.kfe.wallet.adapters.in.observation.KfeColdWalletObservationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Pageable;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.bootstrap.config.bitcoin.KfeBitcoinFinalityPolicy;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KfeOutboundConfirmationMonitorTest {

    @Mock
    private KfeTransactionRepository transactionRepository;
    @Mock
    private KfeExecutionTransactionHelper transactionHelper;
    @Mock
    private ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient;
    @Mock
    private ObjectProvider<KfeColdWalletObservationService> coldObservationService;
    @Mock
    private BitcoinCoreRpcClient core;
    @Mock
    private KfeFinancialMetrics financialMetrics;

    private KfeOutboundConfirmationMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new KfeOutboundConfirmationMonitor(
                transactionRepository,
                transactionHelper,
                bitcoinCoreRpcClient,
                coldObservationService,
                financialMetrics,
                50,
                finalityPolicy(1, 6),
                5,
                300);
        when(bitcoinCoreRpcClient.getIfAvailable()).thenReturn(core);
        lenient().when(coldObservationService.getIfAvailable()).thenReturn(null);
    }

    @Test
    void persistsConfProgressBeforeSettleWhenMinConfirmationsReached() {
        KfeTransactionEntity tx = outbound(KfeTransactionStatus.EXECUTING, 0, "aa".repeat(32));
        UUID txId = tx.getId();
        stubOutboundQueries(List.of(tx), List.of());
        when(core.fetchTransactionChainStatus(tx.getBlockchainTxid()))
                .thenReturn(new BitcoinCoreRpcClient.TransactionChainStatus(
                        BitcoinCoreRpcClient.TransactionChainStatus.ChainState.CONFIRMED,
                        2, "blockhash", 100, null));
        when(transactionHelper.settleOutboundWhenConfirmed(txId, 2)).thenReturn(true);

        monitor.reconcileOutboundConfirmations();

        // Conf touch must run before settle so UI is not stuck at 0 if settle hangs.
        var inOrder = org.mockito.Mockito.inOrder(transactionHelper);
        inOrder.verify(transactionHelper).touchOutboundConfirmations(eq(txId), eq(2), any(), any());
        inOrder.verify(transactionHelper).settleOutboundWhenConfirmed(txId, 2);
    }

    @Test
    void stillTouchesConfsWhenBelowMinWithoutSettling() {
        KfeTransactionEntity tx = outbound(KfeTransactionStatus.EXECUTING, 0, "bb".repeat(32));
        UUID txId = tx.getId();
        monitor = new KfeOutboundConfirmationMonitor(
                transactionRepository,
                transactionHelper,
                bitcoinCoreRpcClient,
                coldObservationService,
                null, // KfeFinancialMetrics (null-safe)
                50,
                finalityPolicy(3, 6),
                5,
                300);
        when(bitcoinCoreRpcClient.getIfAvailable()).thenReturn(core);
        stubOutboundQueries(List.of(tx), List.of());
        when(core.fetchTransactionChainStatus(tx.getBlockchainTxid()))
                .thenReturn(new BitcoinCoreRpcClient.TransactionChainStatus(
                        BitcoinCoreRpcClient.TransactionChainStatus.ChainState.CONFIRMED,
                        1, "blockhash", 100, null));

        monitor.reconcileOutboundConfirmations();

        verify(transactionHelper).touchOutboundConfirmations(eq(txId), eq(1), any(), any());
        verify(transactionHelper, never()).settleOutboundWhenConfirmed(any(), anyInt());
    }

    @Test
    void advancesSettledOutboundRingsWithoutReSettling() {
        KfeTransactionEntity tx = outbound(KfeTransactionStatus.SETTLED, 1, "cc".repeat(32));
        UUID txId = tx.getId();
        stubOutboundQueries(List.of(), List.of(tx));
        when(core.fetchTransactionChainStatus(tx.getBlockchainTxid()))
                .thenReturn(new BitcoinCoreRpcClient.TransactionChainStatus(
                        BitcoinCoreRpcClient.TransactionChainStatus.ChainState.CONFIRMED,
                        4, "blockhash", 100, null));

        monitor.reconcileOutboundConfirmations();

        verify(transactionHelper).touchOutboundConfirmations(eq(txId), eq(4), any(), any());
        verify(transactionHelper, never()).settleOutboundWhenConfirmed(any(), anyInt());
    }

    @Test
    void settleFailureDoesNotPreventConfTouch() {
        KfeTransactionEntity tx = outbound(KfeTransactionStatus.EXECUTING, 0, "dd".repeat(32));
        UUID txId = tx.getId();
        stubOutboundQueries(List.of(tx), List.of());
        when(core.fetchTransactionChainStatus(tx.getBlockchainTxid()))
                .thenReturn(new BitcoinCoreRpcClient.TransactionChainStatus(
                        BitcoinCoreRpcClient.TransactionChainStatus.ChainState.CONFIRMED,
                        3, "blockhash", 100, null));
        when(transactionHelper.settleOutboundWhenConfirmed(txId, 3))
                .thenThrow(new IllegalStateException("audit lock"));

        monitor.reconcileOutboundConfirmations();

        verify(transactionHelper).touchOutboundConfirmations(eq(txId), eq(3), any(), any());
        verify(transactionHelper).settleOutboundWhenConfirmed(txId, 3);
    }

    @Test
    void includesSettledStatusInOutboundQuery() {
        stubOutboundQueries(List.of(), List.of());

        monitor.reconcileOutboundConfirmations();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KfeTransactionStatus>> statuses = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository, atLeastOnce()).findOutboundAwaitingConfirmation(
                eq(KfeRail.ONCHAIN),
                eq(KfeDirection.OUTBOUND),
                statuses.capture(),
                eq(6),
                any(Pageable.class));
        assertThat(statuses.getAllValues().stream().flatMap(List::stream).toList())
                .contains(KfeTransactionStatus.SETTLED);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -2, Integer.MIN_VALUE})
    void negativeObservationUsesBoundHelperWithoutMergingSnapshotOrQueryingInputs(int confirmations) {
        String observedTxid = "ef".repeat(32);
        KfeTransactionEntity tx = outbound(KfeTransactionStatus.EXECUTING, 0, " " + observedTxid + " ");
        stubOutboundQueries(List.of(tx), List.of());
        when(core.fetchTransactionChainStatus(observedTxid)).thenReturn(chainStatus(
                BitcoinCoreRpcClient.TransactionChainStatus.ChainState.CONFLICTED, confirmations));

        monitor.reconcileOutboundConfirmations();

        verify(transactionHelper).markOutboundConflicted(tx.getId(), observedTxid, confirmations);
        verifyNoMoreInteractions(transactionHelper);
        verifyOnlyChainProbeAndNoSnapshotSave(observedTxid);
        assertThat(tx.isConfirmationMonitoringActive()).isTrue();
        assertThat(tx.getReplacementTxid()).isNull();
    }

    @Test
    void negativeSettledObservationIsDelegatedForReorgWithoutResettlingOrMergingSnapshot() {
        KfeTransactionEntity tx = outbound(KfeTransactionStatus.SETTLED, 4, "f0".repeat(32));
        stubOutboundQueries(List.of(), List.of(tx));
        when(core.fetchTransactionChainStatus(tx.getBlockchainTxid())).thenReturn(chainStatus(
                BitcoinCoreRpcClient.TransactionChainStatus.ChainState.CONFLICTED, -1));

        monitor.reconcileOutboundConfirmations();

        verify(transactionHelper).markOutboundConflicted(tx.getId(), tx.getBlockchainTxid(), -1);
        verifyNoMoreInteractions(transactionHelper);
        verifyOnlyChainProbeAndNoSnapshotSave(tx.getBlockchainTxid());
    }

    @ParameterizedTest
    @EnumSource(value = KfeTransactionStatus.class, names = {"EXECUTING", "SETTLED"})
    void disappearedObservationAfterThresholdUsesBoundHelperWithoutRefundOrReplacement(KfeTransactionStatus state) {
        KfeTransactionEntity tx = outbound(state, 2, "f1".repeat(32));
        prepareUncertainObservation(tx, true);

        monitor.reconcileOutboundConfirmations();

        verify(transactionHelper).markOutboundDisappeared(tx.getId(), tx.getBlockchainTxid());
        verifyNoMoreInteractions(transactionHelper);
        verifyOnlyChainProbeAndNoSnapshotSave(tx.getBlockchainTxid());
        assertThat(tx.isConfirmationMonitoringActive()).isTrue();
        assertThat(tx.getReplacementTxid()).isNull();
        assertThat(tx.getConfirmations()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedUncertainObservationDoesNotFallBackToSnapshotSaveOrRefund(boolean disappeared) {
        KfeTransactionEntity tx = outbound(KfeTransactionStatus.EXECUTING, 0, "f2".repeat(32));
        prepareUncertainObservation(tx, disappeared);
        if (disappeared) {
            doThrow(new IllegalStateException("persistence unavailable")).when(transactionHelper)
                    .markOutboundDisappeared(tx.getId(), tx.getBlockchainTxid());
        } else {
            doThrow(new IllegalStateException("persistence unavailable")).when(transactionHelper)
                    .markOutboundConflicted(tx.getId(), tx.getBlockchainTxid(), -1);
        }

        monitor.reconcileOutboundConfirmations();

        if (disappeared) {
            verify(transactionHelper).markOutboundDisappeared(tx.getId(), tx.getBlockchainTxid());
        } else {
            verify(transactionHelper).markOutboundConflicted(tx.getId(), tx.getBlockchainTxid(), -1);
        }
        verifyNoMoreInteractions(transactionHelper);
        verifyOnlyChainProbeAndNoSnapshotSave(tx.getBlockchainTxid());
        assertThat(tx.isConfirmationMonitoringActive()).isTrue();
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void helperTransitionCannotBeOverwrittenByDetachedMonitorSnapshot(boolean disappeared) {
        KfeTransactionEntity snapshot = outbound(KfeTransactionStatus.EXECUTING, 0, "f3".repeat(32));
        KfeTransactionEntity persisted = outbound(KfeTransactionStatus.EXECUTING, 0, snapshot.getBlockchainTxid());
        prepareUncertainObservation(snapshot, disappeared);
        // Simulate a detached read: the helper writes a different current row instance. A later
        // repository merge of the old snapshot would overwrite that committed state.
        lenient().when(transactionRepository.save(any(KfeTransactionEntity.class))).thenAnswer(invocation -> {
            KfeTransactionEntity merged = invocation.getArgument(0);
            persisted.setStatus(merged.getStatus());
            return merged;
        });
        if (disappeared) {
            doAnswer(invocation -> {
                persisted.setStatus(KfeTransactionStatus.REQUIRES_RECONCILIATION);
                return null;
            }).when(transactionHelper).markOutboundDisappeared(snapshot.getId(), snapshot.getBlockchainTxid());
        } else {
            doAnswer(invocation -> {
                persisted.setStatus(KfeTransactionStatus.REQUIRES_RECONCILIATION);
                return null;
            }).when(transactionHelper).markOutboundConflicted(snapshot.getId(), snapshot.getBlockchainTxid(), -1);
        }

        monitor.reconcileOutboundConfirmations();

        assertThat(persisted.getStatus()).isEqualTo(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        assertThat(snapshot.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
        verifyOnlyChainProbeAndNoSnapshotSave(snapshot.getBlockchainTxid());
    }

    private void prepareUncertainObservation(KfeTransactionEntity tx, boolean disappeared) {
        if (tx.getStatus() == KfeTransactionStatus.SETTLED) {
            stubOutboundQueries(List.of(), List.of(tx));
        } else {
            stubOutboundQueries(List.of(tx), List.of());
        }
        if (disappeared) {
            tx.setNetworkNotFoundSince(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(6));
            tx.setNetworkNotFoundCount(4);
        }
        when(core.fetchTransactionChainStatus(tx.getBlockchainTxid())).thenReturn(chainStatus(
                disappeared ? BitcoinCoreRpcClient.TransactionChainStatus.ChainState.NOT_FOUND
                        : BitcoinCoreRpcClient.TransactionChainStatus.ChainState.CONFLICTED,
                disappeared ? 0 : -1));
    }

    private void verifyOnlyChainProbeAndNoSnapshotSave(String observedTxid) {
        verify(core).fetchTransactionChainStatus(observedTxid);
        // In particular, no getRawTransaction/queryOutpoint/findReplacement* call is permitted.
        verifyNoMoreInteractions(core);
        verify(transactionRepository, never()).save(any(KfeTransactionEntity.class));
        verify(transactionRepository, never()).saveAndFlush(any(KfeTransactionEntity.class));
    }

    private static BitcoinCoreRpcClient.TransactionChainStatus chainStatus(
            BitcoinCoreRpcClient.TransactionChainStatus.ChainState state, int confirmations) {
        return new BitcoinCoreRpcClient.TransactionChainStatus(state, confirmations, null, null, null);
    }

    private void stubOutboundQueries(
            List<KfeTransactionEntity> open, List<KfeTransactionEntity> settled) {
        when(transactionRepository.findOutboundAwaitingConfirmation(
                        eq(KfeRail.ONCHAIN),
                        eq(KfeDirection.OUTBOUND),
                        any(),
                        eq(6),
                        any(Pageable.class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<KfeTransactionStatus> statuses =
                            (List<KfeTransactionStatus>) invocation.getArgument(2);
                    if (statuses != null && statuses.contains(KfeTransactionStatus.SETTLED)
                            && statuses.size() == 1) {
                        return settled;
                    }
                    return open;
                });
        when(transactionRepository.findOutboundAwaitingConfirmation(
                        eq(KfeRail.ONCHAIN),
                        eq(KfeDirection.INBOUND),
                        any(),
                        eq(6),
                        any(Pageable.class)))
                .thenReturn(List.of());
    }

    private static KfeTransactionEntity outbound(
            KfeTransactionStatus status, int confs, String txid) {
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);
        tx.setStatus(status);
        tx.setConfirmations(confs);
        tx.setBlockchainTxid(txid);
        tx.setSourceWalletId(UUID.randomUUID());
        tx.setUserId(1L);
        return tx;
    }

    private static KfeBitcoinFinalityPolicy finalityPolicy(int credit, int finality) {
        KfeBitcoinFinalityPolicy policy = new KfeBitcoinFinalityPolicy();
        policy.setCreditConfirmations(credit);
        policy.setFinalityConfirmations(finality);
        policy.setReorgMonitorConfirmations(Math.max(12, finality));
        return policy;
    }
}
