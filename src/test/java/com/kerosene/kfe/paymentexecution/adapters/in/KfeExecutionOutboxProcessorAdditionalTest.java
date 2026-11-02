package com.kerosene.kfe.paymentexecution.adapters.in;

import com.kerosene.kfe.paymentexecution.adapters.out.execution.KfeExecutionTransactionHelper;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.KfeLightningOutboundExecutor;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.KfeOnchainOutboundExecutor;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.KfePreparedExecutionService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import java.util.List;
import java.util.Optional;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionClaimPort;
import com.kerosene.kfe.paymentexecution.adapters.in.transaction.ProcessExecutionAdapter;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.LegacyExecutionStateAdapter;
import com.kerosene.kfe.paymentexecution.config.PaymentExecutionWorkerConfiguration;
import com.kerosene.kfe.adapters.out.rail.custody.CustodyGateway;
import com.kerosene.kfe.adapters.out.rail.onchain.KfeOnchainPaymentGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningPaymentGateway;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeExecutionOutboxProcessorAdditionalTest {

    private final KfeExecutionTransactionHelper transactionHelper = mock(KfeExecutionTransactionHelper.class);
    private final KfeOnchainPaymentGateway onchainPaymentGateway = mock(KfeOnchainPaymentGateway.class);
    private final LightningPaymentGateway lightningPaymentGateway = mock(LightningPaymentGateway.class);
    private final KfePreparedExecutionService prepared = mock(KfePreparedExecutionService.class);
    private final ExecutionClaimPort claims = mock(ExecutionClaimPort.class);
    @BeforeEach
    void ownedPreparedExecution() {
        when(claims.heartbeat(any())).thenReturn(true);
        when(lightningPaymentGateway.isLive()).thenReturn(true);
        when(prepared.load(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            Object value = invocation.getArgument(4) == KfePreparedExecutionService.PayloadType.ONCHAIN
                    ? new KfeOnchainPaymentGateway.PreparedOnchainPayment("raw-test", "txid-123", 500, null, null, null, List.of(), null, null)
                    : new LightningPaymentGateway.PreparedLightningPayment("INVOICE", 42L, null, "wallet", "invoice",
                            null, null, null, "provider-ref", 2100, 15, null, "idempotency", "proof");
            return Optional.of(new KfePreparedExecutionService.StoredPayload<>(value, "ref"));
        });
    }

    private final LegacyExecutionStateAdapter state = new LegacyExecutionStateAdapter(transactionHelper);
    private final KfeExecutionOutboxProcessor processor = new KfeExecutionOutboxProcessor(new ProcessExecutionAdapter(
            new PaymentExecutionWorkerConfiguration().processExecutionService(claims, state,
                    List.of(new KfeOnchainOutboundExecutor(transactionHelper, onchainPaymentGateway, prepared, claims),
                            new KfeLightningOutboundExecutor(transactionHelper, lightningPaymentGateway, prepared, claims)), state)));

    private static KfeExecutionOutboxService.ExecutionClaim claim(UUID outboxId) {
        return new KfeExecutionOutboxService.ExecutionClaim(outboxId, UUID.randomUUID());
    }

    @Test
    void processDoesNothingWhenPreparationDeclinesExecution() {
        UUID outboxId = UUID.randomUUID();
        KfeExecutionOutboxService.ExecutionClaim executionClaim = claim(outboxId);
        when(transactionHelper.prepare(outboxId, executionClaim.claimToken())).thenReturn(
                new KfeExecutionTransactionHelper.PreparationResult(
                        false,
                        "ONCHAIN_OUTBOUND",
                        UUID.randomUUID(),
                        10L,
                        "wallet",
                        UUID.randomUUID(),
                        "bc1qdestination",
                        1000L,
                        25L,
                        null,
                        "idempotency",
                        "proof",
                        null,
                        null,
                        executionClaim.claimToken()));

        processor.process(executionClaim);

        verify(onchainPaymentGateway, never()).broadcastPrepared(any());
        verify(lightningPaymentGateway, never()).payPreparedLightning(any());
    }

    @Test
    void processLightningOutboundSettlesWithPaymentHashAsProviderReference() {
        UUID outboxId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        KfeExecutionOutboxService.ExecutionClaim executionClaim = claim(outboxId);
        KfeExecutionTransactionHelper.PreparationResult prep = new KfeExecutionTransactionHelper.PreparationResult(
                true,
                "LIGHTNING_OUTBOUND",
                transactionId,
                42L,
                "wallet-label",
                walletId,
                "lnbcrt1paymentrequest",
                2100L,
                15L,
                "memo",
                "idempotency",
                "quorum-proof",
                null,
                null,
                executionClaim.claimToken());
        CustodyGateway.PaymentResult result = new CustodyGateway.PaymentResult(
                "provider-ref",
                null,
                "payment-hash",
                "SUCCESS",
                2L,
                "{\"status\":\"ok\"}");

        when(transactionHelper.prepare(outboxId, executionClaim.claimToken())).thenReturn(prep);
        when(lightningPaymentGateway.payPreparedLightning(any())).thenReturn(result);
        when(lightningPaymentGateway.providerName()).thenReturn("lnd");

        processor.process(executionClaim);

        verify(lightningPaymentGateway).payPreparedLightning(any(LightningPaymentGateway.PreparedLightningPayment.class));
        verify(transactionHelper).settleOutboundLightning(
                eq(outboxId),
                eq(transactionId),
                eq(executionClaim.claimToken()),
                eq("lnd"),
                eq("provider-ref"),
                eq(null),
                eq("payment-hash"),
                eq(2L),
                eq(walletId),
                eq("{\"status\":\"ok\"}"));
    }

    @Test
    void processUnsupportedOperationMarksFinalFailure() {
        UUID outboxId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        KfeExecutionOutboxService.ExecutionClaim executionClaim = claim(outboxId);
        when(transactionHelper.prepare(outboxId, executionClaim.claimToken())).thenReturn(
                new KfeExecutionTransactionHelper.PreparationResult(
                        true,
                        "DOGE_OUTBOUND",
                        transactionId,
                        42L,
                        "wallet-label",
                        UUID.randomUUID(),
                        "external",
                        2100L,
                        15L,
                        null,
                        "idempotency",
                        "quorum-proof",
                        null,
                        null,
                        executionClaim.claimToken()));

        processor.process(executionClaim);

        verify(transactionHelper).markFinalFailure(
                eq(outboxId),
                eq(transactionId),
                eq(executionClaim.claimToken()),
                eq("UNSUPPORTED_OPERATION"),
                eq("Unsupported KFE outbox operation."));
    }

    @Test
    void processMissingOnchainDestinationMarksFinalFailure() {
        UUID outboxId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        KfeExecutionOutboxService.ExecutionClaim executionClaim = claim(outboxId);
        when(transactionHelper.prepare(outboxId, executionClaim.claimToken())).thenReturn(
                new KfeExecutionTransactionHelper.PreparationResult(
                        true,
                        "ONCHAIN_OUTBOUND",
                        transactionId,
                        42L,
                        "wallet-label",
                        UUID.randomUUID(),
                        " ",
                        2100L,
                        15L,
                        null,
                        "idempotency",
                        "quorum-proof",
                        null,
                        null,
                        executionClaim.claimToken()));

        processor.process(executionClaim);

        verify(transactionHelper).markFinalFailure(
                eq(outboxId),
                eq(transactionId),
                eq(executionClaim.claimToken()),
                eq("PROVIDER_FINAL_FAILURE"),
                eq("External execution preparation was rejected."));
    }

    @Test
    void processAmbiguousOnchainProviderOutcomeMarksUnknown() {
        UUID outboxId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        KfeExecutionOutboxService.ExecutionClaim executionClaim = claim(outboxId);
        KfeExecutionTransactionHelper.PreparationResult prep = new KfeExecutionTransactionHelper.PreparationResult(
                true,
                "ONCHAIN_OUTBOUND",
                transactionId,
                42L,
                "wallet-label",
                UUID.randomUUID(),
                "bc1qdestination",
                2100L,
                15L,
                null,
                "idempotency",
                "quorum-proof",
                null,
                null,
                executionClaim.claimToken());
        KfeOnchainPaymentGateway.ProviderExecutionAmbiguous ambiguous =
                new KfeOnchainPaymentGateway.ProviderExecutionAmbiguous(
                        "broadcast result unknown",
                        "provider-ref",
                        "{\"ambiguous\":true}",
                        null);

        when(transactionHelper.prepare(outboxId, executionClaim.claimToken())).thenReturn(prep);
        when(onchainPaymentGateway.broadcastPrepared(any())).thenThrow(ambiguous);

        processor.process(executionClaim);

        verify(transactionHelper).markUnknown(
                eq(outboxId),
                eq(transactionId),
                eq(executionClaim.claimToken()),
                eq("txid-123"),
                eq((String) null),
                eq("External execution outcome requires reconciliation."));
    }
}
