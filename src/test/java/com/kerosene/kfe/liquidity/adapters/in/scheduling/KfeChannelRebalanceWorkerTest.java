package com.kerosene.kfe.liquidity.adapters.in.scheduling;

import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.liquidity.adapters.out.persistence.KfeChannelRebalanceQueueService;
import com.kerosene.kfe.wallet.adapters.out.persistence.KfeSystemWalletService;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelRebalanceJobEntity;
import com.kerosene.kfe.adapters.out.rail.channels.LightningChannelGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KfeChannelRebalanceWorkerTest {
    private final KfeChannelRebalanceQueueService queue = mock(KfeChannelRebalanceQueueService.class);
    private final LightningChannelGateway gateway = mock(LightningChannelGateway.class);
    private final KfeChannelRebalanceJobEntity job = new KfeChannelRebalanceJobEntity();
    private final KfeChannelRebalanceQueueService.JobClaim claim =
            new KfeChannelRebalanceQueueService.JobClaim(job.getId(), UUID.randomUUID());
    private final String hash = "a".repeat(64);
    private final LightningChannelGateway.PreparedCircularRebalance prepared =
            new LightningChannelGateway.PreparedCircularRebalance(hash, "ln-invoice", 500, List.of("99"), "02"+"a".repeat(64));
    private KfeChannelRebalanceWorker worker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        job.setChannelPoint("txid:0");
        job.setEstimatedCostSats(500);
        when(gateway.isLive()).thenReturn(true);
        when(queue.findById(job.getId())).thenReturn(Optional.of(job));
        when(queue.claim(eq(job.getId()), anyString(), anyLong())).thenReturn(Optional.of(claim));
        when(queue.heartbeat(claim, 600)).thenReturn(true);
        when(queue.beginDispatch(claim, hash, 500)).thenReturn(true);
        when(gateway.prepareCircularRebalance(any())).thenReturn(prepared);
        worker = new KfeChannelRebalanceWorker(queue, gateway, mock(ObjectProvider.class),
                mock(KfeSystemWalletService.class), mock(KfeBalanceService.class),
                mock(KfeAuditLogService.class), mock(ObjectProvider.class), true, true, 5, 1000, 50000);
    }

    @Test void commitsRecoveryIdentityBeforeSending() {
        when(gateway.submitCircularRebalance(prepared)).thenReturn(
                LightningChannelGateway.CircularRebalanceResult.ok(hash, 0, "{}"));
        worker.executeJob(job.getId());
        var ordered = inOrder(queue, gateway);
        ordered.verify(queue).beginDispatch(claim, hash, 500);
        ordered.verify(gateway).submitCircularRebalance(prepared);
        ordered.verify(queue).finishDispatch(claim, true, 0);
    }

    @ParameterizedTest @ValueSource(strings={"IN_FLIGHT", "UNKNOWN", "ERROR", "UNSUPPORTED"})
    void ambiguousPaymentNeverSettlesOrFallsBack(String state) {
        when(gateway.submitCircularRebalance(prepared)).thenReturn(
                LightningChannelGateway.CircularRebalanceResult.failed(state, null, "pending"));
        worker.executeJob(job.getId());
        verify(queue, never()).finishDispatch(any(), anyBoolean(), anyLong());
        verify(gateway, never()).attemptCircularRebalance(any());
    }

    @Test void expiredExecutionLooksUpSameHashWithoutSending() {
        job.setExecutionPhase("RECONCILING");
        job.setPaymentHash(hash);
        when(gateway.lookupCircularRebalance(hash)).thenReturn(
                LightningChannelGateway.CircularRebalanceResult.ok(hash, 100, "{}"));
        when(queue.pending(5)).thenReturn(List.of(job));
        worker.processBatch(5);
        verify(gateway).lookupCircularRebalance(hash);
        verify(gateway, never()).prepareCircularRebalance(any());
        verify(gateway, never()).submitCircularRebalance(any());
        verify(queue).finishDispatch(claim, true, 100);
    }

    @Test void timeoutRetainsRecoveryState() {
        when(gateway.submitCircularRebalance(prepared)).thenThrow(new IllegalStateException("timeout"));
        worker.executeJob(job.getId());
        verify(queue).beginDispatch(claim, hash, 500);
        verify(queue).failClaim(claim, "Worker failure");
        verify(queue, never()).finishDispatch(any(), anyBoolean(), anyLong());
    }

    @Test void lostLeaseDoesNotSend() {
        when(queue.heartbeat(claim,600)).thenReturn(true,false);
        worker.executeJob(job.getId());
        verify(gateway,never()).submitCircularRebalance(any());
    }

    @Test void legacyUnknownOperationCannotBeReplayed() {
        job.setExecutionPhase("RECONCILING");
        worker.executeJob(job.getId());
        verifyNoInteractionsBeyondLiveness();
    }

    @Test void foreignPaymentEvidenceDoesNotSettle() {
        when(gateway.submitCircularRebalance(prepared)).thenReturn(
                LightningChannelGateway.CircularRebalanceResult.ok("b".repeat(64),100,"{}"));
        worker.executeJob(job.getId());
        verify(queue, never()).finishDispatch(any(),anyBoolean(),anyLong());
    }

    @Test void terminalFailureRequestsAtomicReserveRelease() {
        when(gateway.submitCircularRebalance(prepared)).thenReturn(
                LightningChannelGateway.CircularRebalanceResult.failed("FAILED", "{}", "no route"));
        worker.executeJob(job.getId());
        verify(queue).finishDispatch(claim,false,0);
    }

    private void verifyNoInteractionsBeyondLiveness() {
        verify(gateway,never()).prepareCircularRebalance(any());
        verify(gateway,never()).submitCircularRebalance(any());
        verify(gateway,never()).lookupCircularRebalance(anyString());
    }
}
