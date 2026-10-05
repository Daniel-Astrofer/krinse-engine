package com.kerosene.kfe.paymentexecution.adapters.out.execution;

import com.kerosene.kfe.paymentexecution.adapters.in.KfeExecutionOutboxProcessor;
import com.kerosene.kfe.paymentexecution.adapters.in.KfeExecutionOutboxService;
import com.kerosene.kfe.paymentexecution.domain.exception.KfeExecutionClaimLostException;

import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionClaimPort;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.ProcessExecutionAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.LegacyExecutionStateAdapter;
import com.kerosene.kfe.paymentexecution.config.PaymentExecutionWorkerConfiguration;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import com.kerosene.kfe.paymentexecution.application.result.ExecutionPreparation;
import com.kerosene.kfe.paymentexecution.application.result.ExternalExecutionResult;
import com.kerosene.kfe.adapters.out.rail.custody.CustodyGateway;
import com.kerosene.kfe.adapters.out.rail.onchain.KfeOnchainPaymentGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningPaymentGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual processor + production executors; provider and persistence boundaries are mocked, not live RPC. */
class KfeOutboundExecutionSafetyTest {
    private final KfeExecutionTransactionHelper helper = mock(KfeExecutionTransactionHelper.class);
    private final KfePreparedExecutionService prepared = mock(KfePreparedExecutionService.class);
    private final ExecutionClaimPort claims = mock(ExecutionClaimPort.class);
    private final KfeOnchainPaymentGateway onchain = mock(KfeOnchainPaymentGateway.class);
    private final LightningPaymentGateway lightning = mock(LightningPaymentGateway.class);
    private final KfeOnchainOutboundExecutor chainExecutor = new KfeOnchainOutboundExecutor(helper, onchain, prepared, claims);
    private final KfeLightningOutboundExecutor lightningExecutor = new KfeLightningOutboundExecutor(helper, lightning, prepared, claims);
    private final LegacyExecutionStateAdapter state = new LegacyExecutionStateAdapter(helper);
    private final KfeExecutionOutboxProcessor processor = new KfeExecutionOutboxProcessor(new ProcessExecutionAdapter(
            new PaymentExecutionWorkerConfiguration().processExecutionService(claims, state,
                    List.of(chainExecutor, lightningExecutor), state)));
    private final UUID outboxId = UUID.randomUUID(), txId = UUID.randomUUID(), token = UUID.randomUUID(), walletId = UUID.randomUUID();
    private final KfeExecutionOutboxService.ExecutionClaim legacyClaim = new KfeExecutionOutboxService.ExecutionClaim(outboxId, token);
    private final KfeOnchainPaymentGateway.PreparedOnchainPayment chainPayload = new KfeOnchainPaymentGateway.PreparedOnchainPayment(
            "test-raw", "expected-txid", 20L, null, null, null, List.of(), null, null);
    private final LightningPaymentGateway.PreparedLightningPayment lightningPayload = new LightningPaymentGateway.PreparedLightningPayment(
            "INVOICE", 41L, null, "wallet", "invoice", null, null, "expected-hash", "execution-reference", 1000L, 20L, null, "key", "proof");

    @AfterEach void clearTransactionMarker() { TransactionSynchronizationManager.clear(); }

    private KfeExecutionTransactionHelper.PreparationResult setup(boolean chain, boolean stored) {
        var prep = new KfeExecutionTransactionHelper.PreparationResult(true, chain ? "ONCHAIN_OUTBOUND" : "LIGHTNING_OUTBOUND",
                txId, 41L, "wallet", walletId, "destination", 1000L, 20L, null, "key", "proof", 2L, 6, token);
        when(claims.heartbeat(new ExecutionClaim(outboxId, token))).thenReturn(true);
        when(helper.prepare(outboxId, token)).thenReturn(prep);
        when(lightning.isLive()).thenReturn(true);
        when(lightning.providerName()).thenReturn("lightning-test");
        when(onchain.providerName()).thenReturn("chain-test");
        when(prepared.load(any(), any(), any(), any(), any(), any())).thenReturn(stored
                ? Optional.of(new KfePreparedExecutionService.StoredPayload<>(chain ? chainPayload : lightningPayload, "execution-reference"))
                : Optional.empty());
        when(prepared.persistIfAbsent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new KfePreparedExecutionService.StoredPayload<>(chain ? chainPayload : lightningPayload, "execution-reference"));
        when(onchain.prepareOnchain(any())).thenReturn(chainPayload);
        when(lightning.prepareLightning(any())).thenReturn(lightningPayload);
        when(onchain.broadcastPrepared(chainPayload)).thenReturn(new KfeOnchainPaymentGateway.PaymentResult(
                "provider-ref", "expected-txid", null, "BROADCASTED", 20L, "{}"));
        when(lightning.payPreparedLightning(lightningPayload)).thenReturn(new CustodyGateway.PaymentResult(
                "provider-ref", null, "expected-hash", "SUCCEEDED", 20L, "{}"));
        return prep;
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void persistsPreparationThenRenewsOwnershipImmediatelyBeforeProvider(boolean chain) {
        setup(chain, false);
        processor.process(legacyClaim);
        var order = inOrder(prepared, claims, onchain, lightning, helper);
        order.verify(claims).heartbeat(new ExecutionClaim(outboxId, token));
        order.verify(helper).prepare(outboxId, token);
        order.verify(claims).heartbeat(new ExecutionClaim(outboxId, token));
        order.verify(prepared).load(eq(outboxId), eq(txId), eq(token), any(), any(), any());
        if (chain) { order.verify(onchain).prepareOnchain(any()); }
        else { order.verify(lightning).prepareLightning(any()); }
        order.verify(prepared).persistIfAbsent(eq(outboxId), eq(txId), eq(token), any(), any(), any(), any(), any());
        order.verify(claims).heartbeat(new ExecutionClaim(outboxId, token));
        if (chain) {
            order.verify(onchain).broadcastPrepared(chainPayload);
            order.verify(helper).recordOutboundBroadcast(eq(outboxId), eq(txId), eq(token), any(), any(), any(), anyLong(), eq(walletId), any());
        } else {
            order.verify(lightning).payPreparedLightning(lightningPayload);
            order.verify(helper).settleOutboundLightning(eq(outboxId), eq(txId), eq(token), any(), any(), any(), any(), anyLong(), eq(walletId), any());
        }
        noFailure();
    }

    @Test
    void productionExecutorsExposeTheApplicationPortWithoutTheExternalExecutionBridge() {
        setup(true, true);
        var result = chainExecutor.execute(
                new ExecutionClaim(outboxId, token),
                new ExecutionPreparation("ONCHAIN_OUTBOUND", txId, 41L, "wallet", walletId,
                        "destination", 1000L, 20L, null, "key", "proof", 2L, 6));

        assertThat(result).isEqualTo(ExternalExecutionResult.completed());
        verify(onchain).broadcastPrepared(chainPayload);
        verify(helper).recordOutboundBroadcast(eq(outboxId), eq(txId), eq(token), any(), any(), any(), anyLong(),
                eq(walletId), any());
    }

    @Test
    void directApplicationPortPreservesUncertainProviderOutcome() {
        setup(true, true);
        when(onchain.broadcastPrepared(any())).thenThrow(new IllegalArgumentException("provider unavailable"));

        var result = chainExecutor.execute(
                new ExecutionClaim(outboxId, token),
                new ExecutionPreparation("ONCHAIN_OUTBOUND", txId, 41L, "wallet", walletId,
                        "destination", 1000L, 20L, null, "key", "proof", 2L, 6));

        assertThat(result.outcome()).isEqualTo(ExternalExecutionResult.Outcome.UNKNOWN);
        assertThat(result.providerReference()).isEqualTo("expected-txid");
        verify(helper, never()).recordOutboundBroadcast(any(), any(), any(), any(), any(), any(), anyLong(), any(), any());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void lostLeaseAfterPreparationNeverCallsProviderOrReleasesPreparedResources(boolean chain) {
        setup(chain, false);
        when(claims.heartbeat(any())).thenReturn(true, true, false);
        processor.process(legacyClaim);
        verify(onchain, never()).broadcastPrepared(any());
        verify(lightning, never()).payPreparedLightning(any());
        verify(onchain, never()).releasePrepared(any());
        noFailure();
        verify(helper, never()).markUnknown(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void apparentlyPermanentErrorFromProviderIsStillUnknownOnceRpcStarted(boolean chain) {
        setup(chain, true);
        if (chain) { when(onchain.broadcastPrepared(any())).thenThrow(new IllegalArgumentException("SECRET-RPC-ERROR")); }
        else { when(lightning.payPreparedLightning(any())).thenThrow(new UnsupportedOperationException("SECRET-RPC-ERROR")); }
        processor.process(legacyClaim);
        verify(helper).markUnknown(eq(outboxId), eq(txId), eq(token),
                eq(chain ? "expected-txid" : "execution-reference"), isNull(),
                eq("External execution outcome requires reconciliation."));
        noFailure();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void localPersistenceFailureAfterSuccessCannotBecomeFinalFailure(boolean chain) {
        setup(chain, true);
        if (chain) { doThrow(new IllegalArgumentException("SECRET-DB-ERROR")).when(helper)
                .recordOutboundBroadcast(any(), any(), any(), any(), any(), any(), anyLong(), any(), any()); }
        else { doThrow(new IllegalArgumentException("SECRET-DB-ERROR")).when(helper)
                .settleOutboundLightning(any(), any(), any(), any(), any(), any(), any(), anyLong(), any(), any()); }
        processor.process(legacyClaim);
        verify(helper).markUnknown(eq(outboxId), eq(txId), eq(token),
                eq(chain ? "expected-txid" : "execution-reference"), eq("{}"),
                eq("External execution outcome requires reconciliation."));
        noFailure();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void failedUnknownPersistencePropagatesWithoutFallbackToRelease(boolean chain) {
        setup(chain, true);
        if (chain) { when(onchain.broadcastPrepared(any())).thenThrow(new RuntimeException()); }
        else { when(lightning.payPreparedLightning(any())).thenThrow(new RuntimeException()); }
        var lost = new KfeExecutionClaimLostException(outboxId);
        doThrow(lost).when(helper).markUnknown(any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> processor.process(legacyClaim)).isSameAs(lost);
        noFailure();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void missingOrMismatchedAcknowledgementIsUnknown(boolean chain) {
        setup(chain, true);
        if (chain) { when(onchain.broadcastPrepared(any())).thenReturn(new KfeOnchainPaymentGateway.PaymentResult(
                "ref", "wrong-txid", null, "BROADCASTED", 20L, "{}")); }
        else { when(lightning.payPreparedLightning(any())).thenReturn(new CustodyGateway.PaymentResult(
                "ref", null, null, "SUCCEEDED", 20L, "{}")); }
        processor.process(legacyClaim);
        verify(helper).markUnknown(eq(outboxId), eq(txId), eq(token), any(), any(), any());
        noFailure();
        verify(helper, never()).recordOutboundBroadcast(any(), any(), any(), any(), any(), any(), anyLong(), any(), any());
        verify(helper, never()).settleOutboundLightning(any(), any(), any(), any(), any(), any(), any(), anyLong(), any(), any());
    }

    @ParameterizedTest @ValueSource(strings = {"PENDING", "FAILED", "UNKNOWN", ""})
    void nonSuccessfulLightningAcknowledgementCannotSettle(String status) {
        setup(false, true);
        when(lightning.payPreparedLightning(any())).thenReturn(new CustodyGateway.PaymentResult(
                "ref", null, "expected-hash", status, 20L, "{}"));
        processor.process(legacyClaim);
        verify(helper).markUnknown(any(), any(), any(), any(), any(), any());
        verify(helper, never()).settleOutboundLightning(any(), any(), any(), any(), any(), any(), any(), anyLong(), any(), any());
        noFailure();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void nullProviderResponseCannotBecomeFinalFailure(boolean chain) {
        setup(chain, true);
        if (chain) { when(onchain.broadcastPrepared(any())).thenReturn(null); }
        else { when(lightning.payPreparedLightning(any())).thenReturn(null); }
        processor.process(legacyClaim);
        verify(helper).markUnknown(eq(outboxId), eq(txId), eq(token), any(), isNull(), any());
        noFailure();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void ownershipLostAfterProviderSuccessDoesNotAuthorizeRefund(boolean chain) {
        setup(chain, true);
        var lost = new KfeExecutionClaimLostException(outboxId);
        if (chain) { doThrow(lost).when(helper)
                .recordOutboundBroadcast(any(), any(), any(), any(), any(), any(), anyLong(), any(), any()); }
        else { doThrow(lost).when(helper)
                .settleOutboundLightning(any(), any(), any(), any(), any(), any(), any(), anyLong(), any(), any()); }
        doThrow(lost).when(helper).markUnknown(any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> processor.process(legacyClaim)).isSameAs(lost);
        noFailure();
    }

    @Test void activeTransactionIsRejectedBeforeAnyBoundaryIncludingDirectExecutorCalls() {
        var prep = setup(true, true);
        clearInvocations(helper, claims, prepared, onchain, lightning);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> processor.process(legacyClaim)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> chainExecutor.execute(outboxId, prep)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> lightningExecutor.execute(outboxId, prep)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(helper, claims, prepared, onchain, lightning);
    }

    private void noFailure() {
        verify(helper, never()).markFinalFailure(any(), any(), any(), any(), any());
        verify(helper, never()).markRetryableFailure(any(), any(), any(), any(), any());
    }
}
