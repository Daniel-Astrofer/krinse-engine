package com.kerosene.kfe.liquidity.adapters.in;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.liquidity.adapters.in.scheduling.KfeChannelMeshInjectReconciler;
import com.kerosene.kfe.liquidity.adapters.out.persistence.KfeChannelRebalanceQueueService;
import com.kerosene.kfe.wallet.adapters.out.persistence.KfeSystemWalletService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.liquidity.adapters.in.ChannelDecisionFlag;
import com.kerosene.kfe.liquidity.adapters.in.ChannelDecisionResult;
import com.kerosene.kfe.liquidity.adapters.in.ChannelFlagEvaluation;
import com.kerosene.kfe.liquidity.adapters.in.KfeChannelDecisionService;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeChannelDecisionResponse;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeOpenChannelRequest;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelOperationDecisionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelOperationType;
import com.kerosene.kfe.adapters.out.rail.channels.ChannelsMeshInjectGateway;
import com.kerosene.kfe.adapters.out.rail.channels.LightningChannelGateway;
import com.kerosene.kfe.adapters.out.persistence.repository.liquidity.KfeChannelOperationDecisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeChannelLifecycleServiceMeshInjectTest {

    private final KfeChannelDecisionService decisionService = mock(KfeChannelDecisionService.class);
    private final LightningChannelGateway channelGateway = mock(LightningChannelGateway.class);
    private final ChannelsMeshInjectGateway channelsMeshInject = mock(ChannelsMeshInjectGateway.class);
    private final KfeChannelOperationDecisionRepository decisionRepository =
            mock(KfeChannelOperationDecisionRepository.class);
    private final KfeChannelRebalanceQueueService rebalanceQueueService =
            mock(KfeChannelRebalanceQueueService.class);
    private final KfeHashService hashService = mock(KfeHashService.class);
    private final KfeSystemWalletService systemWalletService = mock(KfeSystemWalletService.class);
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);
    private final Map<UUID, KfeChannelOperationDecisionEntity> decisions = new HashMap<>();

    @BeforeEach
    void setUp() {
        decisions.clear();
        when(hashService.sha256(anyString())).thenReturn("proposal-hash");
        when(systemWalletService.requireProfitWalletId()).thenReturn(UUID.randomUUID());
        when(channelGateway.isLive()).thenReturn(true);
        when(channelGateway.listChannels()).thenReturn(List.of());
        when(channelGateway.listPendingChannels()).thenReturn(List.of());
        when(channelGateway.newOnchainAddress(anyString())).thenReturn("tb1qfundaddr");
        when(channelGateway.confirmedOnchainBalanceSats()).thenReturn(50_000_000L);
        when(decisionService.evaluateOpen(anyString(), anyLong(), anyLong(), eq(true), anyString()))
                .thenReturn(
                        new ChannelDecisionResult(
                                KfeChannelOperationType.OPEN,
                                List.of(
                                        ChannelFlagEvaluation.pass(
                                                ChannelDecisionFlag.V_CAPITAL_MINIMO, "OK"),
                                        ChannelFlagEvaluation.pass(
                                                ChannelDecisionFlag.V_TAXA_ONCHAIN_BAIXA, "OK"),
                                        ChannelFlagEvaluation.pass(
                                                ChannelDecisionFlag.V_SAIDA_ANCORA, "OK"),
                                        ChannelFlagEvaluation.pass(
                                                ChannelDecisionFlag.V_AUTORIZACAO_MPC, "OK"),
                                        ChannelFlagEvaluation.pass(
                                                ChannelDecisionFlag.V_DENYLIST_PEER, "OK"),
                                        ChannelFlagEvaluation.pass(
                                                ChannelDecisionFlag.V_CHANNELS_MESH_INJECT,
                                                "CHANNELS_INJECT_READY"))));
        when(decisionRepository.save(any(KfeChannelOperationDecisionEntity.class)))
                .thenAnswer(
                        inv -> {
                            KfeChannelOperationDecisionEntity e = inv.getArgument(0);
                            decisions.put(e.getId(), e);
                            return e;
                        });
        when(decisionRepository.findById(any(UUID.class)))
                .thenAnswer(
                        inv -> {
                            UUID id = inv.getArgument(0);
                            return Optional.of(
                                    decisions.computeIfAbsent(
                                            id, KfeChannelLifecycleServiceMeshInjectTest::entityWithId));
                        });
        when(decisionRepository.findLatestResumableOpen(anyString(), anyLong(), any()))
                .thenReturn(Optional.empty());
        when(channelsMeshInject.fundOpen(anyString(), anyLong(), anyString()))
                .thenAnswer(
                        inv ->
                                ChannelsMeshInjectGateway.FundResult.ok(
                                        "meshfundtxid", "CHANNELS_INJECT_FUNDED_ONCHAIN"));
    }

    private static KfeChannelOperationDecisionEntity entityWithId(UUID id) {
        KfeChannelOperationDecisionEntity e = new KfeChannelOperationDecisionEntity();
        try {
            var field = KfeChannelOperationDecisionEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(e, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        e.setOperation(KfeChannelOperationType.OPEN);
        e.setPassed(true);
        e.setPeerPubkey("02abcd");
        e.setAmountSats(10_000_000L);
        e.setFlagsJson("{}");
        e.setDecisionReason("AND_PASS");
        e.setExecuted(false);
        return e;
    }

    private KfeChannelLifecycleService service(boolean requireInject) {
        return new KfeChannelLifecycleService(
                decisionService,
                channelGateway,
                channelsMeshInject,
                decisionRepository,
                rebalanceQueueService,
                hashService,
                systemWalletService,
                auditLogService,
                new ObjectMapper(),
                true,
                80,
                requireInject,
                true);
    }

    private static KfeOpenChannelRequest openRequest() {
        return new KfeOpenChannelRequest("02abcd", 10_000_000L, 10L, true, false, false);
    }

    @Test
    void openChannelReservesFundsOpensThenCommits() {
        when(channelsMeshInject.reserveOpen(anyString(), eq(10_000_000L), eq("02abcd")))
                .thenAnswer(
                        inv ->
                                ChannelsMeshInjectGateway.DebitResult.ok(
                                        inv.getArgument(0), "CHANNELS_INJECT_RESERVED"));
        when(channelsMeshInject.commitOpen(anyString()))
                .thenReturn(ChannelsMeshInjectGateway.InjectResult.ok("CHANNELS_INJECT_COMMITTED"));
        when(channelGateway.openChannel(any()))
                .thenReturn(
                        new LightningChannelGateway.OpenChannelResult(
                                "txid", "0", "txid:0", "{}"));

        KfeChannelDecisionResponse response = service(true).openChannel(openRequest());

        assertThat(response.executed()).isTrue();
        assertThat(response.providerReference()).startsWith("MESH:channels-inject-open-");
        ArgumentCaptor<String> intentCaptor = ArgumentCaptor.forClass(String.class);
        verify(channelsMeshInject)
                .reserveOpen(intentCaptor.capture(), eq(10_000_000L), eq("02abcd"));
        verify(channelsMeshInject)
                .fundOpen(eq(intentCaptor.getValue()), eq(10_000_000L), eq("tb1qfundaddr"));
        verify(channelGateway).openChannel(any());
        verify(channelsMeshInject).commitOpen(intentCaptor.getValue());
        verify(channelsMeshInject, never()).releaseOpen(anyString(), anyLong(), anyString());
    }

    @Test
    void openChannelReleasesReservationWhenLndOpenFails() {
        when(channelsMeshInject.reserveOpen(anyString(), eq(10_000_000L), eq("02abcd")))
                .thenAnswer(
                        inv ->
                                ChannelsMeshInjectGateway.DebitResult.ok(
                                        inv.getArgument(0), "CHANNELS_INJECT_RESERVED"));
        when(channelsMeshInject.releaseOpen(anyString(), eq(10_000_000L), eq("02abcd")))
                .thenReturn(ChannelsMeshInjectGateway.InjectResult.ok("CHANNELS_INJECT_RELEASED"));
        when(channelGateway.openChannel(any()))
                .thenThrow(new IllegalStateException("lnd down"));

        KfeChannelDecisionResponse response = service(true).openChannel(openRequest());

        assertThat(response.executed()).isFalse();
        assertThat(response.decisionReason()).isEqualTo("OPEN_FAILED_AFTER_DEBIT:COMPENSATED");
        verify(channelsMeshInject).releaseOpen(anyString(), eq(10_000_000L), eq("02abcd"));
        verify(channelsMeshInject, never()).commitOpen(anyString());
    }

    @Test
    void openChannelRefusesDoubleOpenToSamePeer() {
        when(channelGateway.listChannels())
                .thenReturn(
                        List.of(
                                new LightningChannelGateway.ChannelSnapshot(
                                        "txid:0",
                                        "02ABCD",
                                        true,
                                        10_000_000L,
                                        5_000_000L,
                                        5_000_000L,
                                        0,
                                        true,
                                        100L)));

        KfeChannelDecisionResponse response = service(true).openChannel(openRequest());

        assertThat(response.executed()).isFalse();
        assertThat(response.decisionReason()).isEqualTo("CHANNEL_ALREADY_OPEN");
        verify(channelsMeshInject, never()).reserveOpen(anyString(), anyLong(), anyString());
        verify(channelGateway, never()).openChannel(any());
    }

    @Test
    void openChannelRefusesPendingOpenToSamePeer() {
        when(channelGateway.listPendingChannels())
                .thenReturn(
                        List.of(
                                new LightningChannelGateway.PendingChannelSnapshot(
                                        "02ABCD", "pending:0", "PENDING_OPEN", 10_000_000L)));

        KfeChannelDecisionResponse response = service(true).openChannel(openRequest());

        assertThat(response.executed()).isFalse();
        assertThat(response.decisionReason()).isEqualTo("CHANNEL_ALREADY_OPEN");
        verify(channelsMeshInject, never()).reserveOpen(anyString(), anyLong(), anyString());
        verify(channelGateway, never()).openChannel(any());
    }

    @Test
    void openChannelRetriesCommitWhenAlreadyOpenedPending() {
        UUID decisionId = UUID.randomUUID();
        KfeChannelOperationDecisionEntity pending = entityWithId(decisionId);
        pending.setMeshIntentId("channels-inject-open-" + decisionId);
        pending.setMeshInjectPhase(KfeChannelLifecycleService.PHASE_OPENED_COMMIT_PENDING);
        pending.setChannelPoint("txid:0");
        pending.setLndFundingAddress("tb1qfundaddr");
        pending.setDecisionReason("OPENED_COMMIT_PENDING");

        when(decisionRepository.findLatestResumableOpen(anyString(), anyLong(), any()))
                .thenReturn(Optional.of(pending));
        when(decisionRepository.findById(decisionId)).thenReturn(Optional.of(pending));
        when(channelsMeshInject.commitOpen("channels-inject-open-" + decisionId))
                .thenReturn(ChannelsMeshInjectGateway.InjectResult.ok("CHANNELS_INJECT_COMMITTED"));

        KfeChannelDecisionResponse response = service(true).openChannel(openRequest());

        assertThat(response.executed()).isTrue();
        assertThat(response.decisionReason()).isEqualTo("EXECUTED");
        verify(channelsMeshInject, never()).reserveOpen(anyString(), anyLong(), anyString());
        verify(channelGateway, never()).openChannel(any());
        verify(channelsMeshInject, times(1)).commitOpen("channels-inject-open-" + decisionId);
    }

    @Test
    void reconcilerRetriesCommitAndReleasesOrphans() {
        UUID commitId = UUID.randomUUID();
        KfeChannelOperationDecisionEntity commitPending = entityWithId(commitId);
        commitPending.setMeshIntentId("channels-inject-open-" + commitId);
        commitPending.setMeshInjectPhase(KfeChannelLifecycleService.PHASE_OPENED_COMMIT_PENDING);

        UUID orphanId = UUID.randomUUID();
        KfeChannelOperationDecisionEntity orphan = entityWithId(orphanId);
        orphan.setMeshIntentId("channels-inject-open-" + orphanId);
        orphan.setMeshInjectPhase(KfeChannelLifecycleService.PHASE_RESERVED);

        when(decisionRepository.findByMeshInjectPhaseAndExecutedFalseOrderByCreatedAtAsc(
                        eq(KfeChannelLifecycleService.PHASE_OPENED_COMMIT_PENDING), any(Pageable.class)))
                .thenReturn(List.of(commitPending));
        when(decisionRepository.findOrphanedReserves(any(), any(Pageable.class)))
                .thenReturn(List.of(orphan));
        when(decisionRepository.findById(commitId)).thenReturn(Optional.of(commitPending));
        when(channelsMeshInject.commitOpen("channels-inject-open-" + commitId))
                .thenReturn(ChannelsMeshInjectGateway.InjectResult.ok("CHANNELS_INJECT_COMMITTED"));
        when(channelsMeshInject.releaseOpen(eq("channels-inject-open-" + orphanId), anyLong(), anyString()))
                .thenReturn(ChannelsMeshInjectGateway.InjectResult.ok("CHANNELS_INJECT_RELEASED"));

        KfeChannelLifecycleService lifecycle = service(true);
        KfeChannelMeshInjectReconciler reconciler =
                new KfeChannelMeshInjectReconciler(
                        decisionRepository, channelsMeshInject, lifecycle, true, 20, 10);

        assertThat(reconciler.retryPendingCommits(5)).isEqualTo(1);
        assertThat(commitPending.getMeshInjectPhase())
                .isEqualTo(KfeChannelLifecycleService.PHASE_COMMITTED);
        assertThat(reconciler.releaseOrphanedReserves(5)).isEqualTo(1);
        assertThat(orphan.getMeshInjectPhase()).isEqualTo(KfeChannelLifecycleService.PHASE_RELEASED);
        verify(channelsMeshInject).commitOpen("channels-inject-open-" + commitId);
        verify(channelsMeshInject)
                .releaseOpen(eq("channels-inject-open-" + orphanId), eq(10_000_000L), eq("02abcd"));
    }

    @Test
    void resumeReusesStableIntentWithoutMintingNewDecision() {
        UUID decisionId = UUID.randomUUID();
        KfeChannelOperationDecisionEntity reserved = entityWithId(decisionId);
        reserved.setMeshIntentId("channels-inject-open-" + decisionId);
        reserved.setMeshInjectPhase(KfeChannelLifecycleService.PHASE_RESERVED);
        reserved.setLndFundingAddress("tb1qfundaddr");

        when(decisionRepository.findLatestResumableOpen(anyString(), anyLong(), any()))
                .thenReturn(Optional.of(reserved));
        when(decisionRepository.findById(decisionId)).thenReturn(Optional.of(reserved));
        when(channelsMeshInject.fundOpen(anyString(), anyLong(), anyString()))
                .thenReturn(ChannelsMeshInjectGateway.FundResult.ok("meshfundtxid", "FUNDED"));
        when(channelsMeshInject.commitOpen(anyString()))
                .thenReturn(ChannelsMeshInjectGateway.InjectResult.ok("COMMITTED"));
        when(channelGateway.openChannel(any()))
                .thenReturn(
                        new LightningChannelGateway.OpenChannelResult(
                                "txid", "0", "txid:0", "{}"));

        KfeChannelDecisionResponse response = service(true).openChannel(openRequest());

        assertThat(response.id()).isEqualTo(decisionId);
        assertThat(response.executed()).isTrue();
        verify(decisionService, never())
                .evaluateOpen(anyString(), anyLong(), anyLong(), eq(true), anyString());
        verify(channelsMeshInject, never()).reserveOpen(anyString(), anyLong(), anyString());
        verify(channelsMeshInject)
                .fundOpen(eq("channels-inject-open-" + decisionId), eq(10_000_000L), eq("tb1qfundaddr"));
    }
}
