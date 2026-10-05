package com.kerosene.kfe.liquidity.adapters.in.scheduling;

import com.kerosene.kfe.liquidity.adapters.in.KfeChannelLifecycleService;
import com.kerosene.kfe.liquidity.adapters.out.observability.KfeCapacitySignalStore;
import com.kerosene.kfe.liquidity.adapters.out.observability.KfeLightningOpsMetrics;
import com.kerosene.kfe.liquidity.adapters.out.persistence.KfeChannelCapacityQueueService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.kerosene.kfe.liquidity.adapters.in.ChannelDecisionResult;
import com.kerosene.kfe.liquidity.adapters.in.ChannelFlagEvaluation;
import com.kerosene.kfe.liquidity.adapters.in.ChannelDecisionFlag;
import com.kerosene.kfe.liquidity.adapters.in.KfeChannelDecisionService;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeChannelDecisionResponse;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeOpenChannelRequest;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelCapacityJobEntity;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelOperationType;
import com.kerosene.kfe.adapters.out.rail.channels.LightningChannelGateway;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeChannelCapacityControllerTest {

    private final LightningChannelGateway gateway = mock(LightningChannelGateway.class);
    private final KfeChannelDecisionService decisionService = mock(KfeChannelDecisionService.class);
    private final KfeChannelLifecycleService lifecycle = mock(KfeChannelLifecycleService.class);
    private final KfeChannelCapacityQueueService queue = mock(KfeChannelCapacityQueueService.class);
    private final KfeCapacitySignalStore signals = new KfeCapacitySignalStore();
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KfeLightningOpsMetrics> metrics = mock(ObjectProvider.class);

    private KfeChannelCapacityController controller;

    @BeforeEach
    void setUp() {
        when(metrics.getIfAvailable()).thenReturn(null);
        when(gateway.isLive()).thenReturn(true);
        when(gateway.listChannels()).thenReturn(List.of());
        controller = new KfeChannelCapacityController(
                gateway,
                decisionService,
                lifecycle,
                queue,
                signals,
                metrics,
                true,
                true,
                true,
                900_000L,
                2L,
                10_000_000L,
                5_000L,
                50_000L,
                10L,
                3,
                2L,
                "03peerpubkey",
                10_000_000L);
    }

    @Test
    void doesNotOpenWhenLiquidityRejectsBelowThreshold() {
        signals.recordLiquidityReject(); // threshold=2
        controller.evaluateOpens();
        verify(queue, never()).enqueueOpenIfAbsent(
                anyString(), anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void enqueuesOpenWhenLiquidityStressAndGatePasses() {
        signals.recordLiquidityReject();
        signals.recordLiquidityReject();
        when(queue.pendingOpenCount()).thenReturn(0L);
        when(decisionService.evaluateOpen(anyString(), anyLong(), anyLong(), anyBoolean(), anyString()))
                .thenReturn(new ChannelDecisionResult(
                        KfeChannelOperationType.OPEN,
                        List.of(ChannelFlagEvaluation.pass(ChannelDecisionFlag.V_CAPITAL_MINIMO, "OK"))));
        UUID decisionId = UUID.randomUUID();
        when(lifecycle.evaluateOpen(any(KfeOpenChannelRequest.class)))
                .thenReturn(new KfeChannelDecisionResponse(
                        decisionId,
                        KfeChannelOperationType.OPEN,
                        true,
                        false,
                        "03peerpubkey",
                        null,
                        10_000_000L,
                        "OK",
                        null,
                        "{}",
                        LocalDateTime.now()));
        KfeChannelCapacityJobEntity job = new KfeChannelCapacityJobEntity();
        when(queue.enqueueOpenIfAbsent(
                        eq("03peerpubkey"),
                        eq(10_000_000L),
                        eq(5_000L),
                        eq(50_000L),
                        anyString(),
                        eq(decisionId)))
                .thenReturn(Optional.of(job));

        controller.evaluateOpens();

        verify(queue).enqueueOpenIfAbsent(
                eq("03peerpubkey"),
                eq(10_000_000L),
                eq(5_000L),
                eq(50_000L),
                anyString(),
                eq(decisionId));
    }
}
