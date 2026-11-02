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
import static org.mockito.Mockito.*;

class KfeExecutionOutboxProcessorTest {

    private final KfeExecutionTransactionHelper transactionHelper = mock(KfeExecutionTransactionHelper.class);
    private final KfeOnchainPaymentGateway onchainCustodyPort = mock(KfeOnchainPaymentGateway.class);
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
                    List.of(new KfeOnchainOutboundExecutor(transactionHelper, onchainCustodyPort, prepared, claims),
                            new KfeLightningOutboundExecutor(transactionHelper, lightningPaymentGateway, prepared, claims)), state)));

    @Test
    void processOnchainOutboundDelegatesToHelperAndGateway() {
        UUID outboxId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();

        KfeExecutionTransactionHelper.PreparationResult prep = new KfeExecutionTransactionHelper.PreparationResult(
                true,
                "ONCHAIN_OUTBOUND",
                txId,
                456L,
                "wallet-label",
                walletId,
                "1BitcoinAddress",
                50000L,
                500L,
                "memo-test",
                "idemp-key",
                "quorum-proposal",
                null,
                null,
                claimToken
        );

        when(transactionHelper.prepare(outboxId, claimToken)).thenReturn(prep);

        KfeOnchainPaymentGateway.PaymentResult paymentResult = new KfeOnchainPaymentGateway.PaymentResult(
                "ref-123",
                "txid-123",
                "hash-123",
                "SUCCESS",
                500L,
                "{}"
        );
        when(onchainCustodyPort.broadcastPrepared(any())).thenReturn(paymentResult);
        when(onchainCustodyPort.providerName()).thenReturn("btc-core");

        processor.process(new KfeExecutionOutboxService.ExecutionClaim(outboxId, claimToken));

        verify(transactionHelper).prepare(outboxId, claimToken);
        verify(onchainCustodyPort).broadcastPrepared(any());
        verify(transactionHelper).recordOutboundBroadcast(
                eq(outboxId),
                eq(txId),
                eq(claimToken),
                eq("btc-core"),
                eq("txid-123"),
                eq("txid-123"),
                eq(500L),
                eq(walletId),
                eq("{}")
        );
    }

    @Test
    void processDoesNotCallProviderWhenPreparationDoesNotProceed() {
        UUID outboxId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        KfeExecutionTransactionHelper.PreparationResult terminal = new KfeExecutionTransactionHelper.PreparationResult(
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                0L,
                0L,
                null,
                null,
                null,
                null,
                null,
                claimToken
        );
        when(transactionHelper.prepare(outboxId, claimToken)).thenReturn(terminal);

        processor.process(new KfeExecutionOutboxService.ExecutionClaim(outboxId, claimToken));

        verify(transactionHelper).prepare(outboxId, claimToken);
        verifyNoInteractions(onchainCustodyPort, lightningPaymentGateway);
        verify(transactionHelper, never()).markFinalFailure(any(), any(), any(), any(), any());
        verify(transactionHelper, never()).markRetryableFailure(any(), any(), any(), any(), any());
    }
}
