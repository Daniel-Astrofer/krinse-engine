package com.kerosene.kfe.liquidity.adapters.in;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.kerosene.kfe.adapters.out.rail.channels.LightningChannelGateway;
import com.kerosene.kfe.liquidity.adapters.out.lightning.KfeLightningJammingGuard;
import com.kerosene.kfe.paymentexecution.adapters.out.vault.KfeQuorumGateway;
import com.kerosene.kfe.wallet.adapters.out.persistence.KfeSystemWalletService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KfeChannelDecisionServiceTest {

    private final KfeQuorumGateway quorumGateway = mock(KfeQuorumGateway.class);
    private final KfeSystemWalletService systemWalletService = mock(KfeSystemWalletService.class);
    private final KfeLightningJammingGuard jammingGuard = mock(KfeLightningJammingGuard.class);
    private KfeChannelDecisionService service;

    @BeforeEach
    void setUp() {
        when(jammingGuard.evaluate())
                .thenReturn(KfeLightningJammingGuard.JammingCheck.allowed("HTLC_OK:0"));
        when(quorumGateway.requireHealthyUnanimousConsensus(anyString()))
                .thenReturn(new KfeQuorumGateway.Result(3, 3));
        when(systemWalletService.requireProfitWalletId()).thenReturn(UUID.randomUUID());
        service = new KfeChannelDecisionService(
                quorumGateway,
                systemWalletService,
                jammingGuard,
                new com.kerosene.kfe.adapters.out.rail.channels.FailClosedChannelsMeshInjectGateway(),
                10_000_000L,
                50L,
                0.20d,
                5_000L,
                1_000L,
                "badpeer",
                true,
                false);
    }

    @Test
    void openPassesWhenAllStructuralFlagsHold() {
        ChannelDecisionResult result = service.evaluateOpen(
                "goodpeer",
                10_000_000L,
                10L,
                true,
                "proposal-hash");
        assertThat(result.passed()).isTrue();
        assertThat(result.operation().name()).isEqualTo("OPEN");
    }

    @Test
    void openFailsCapitalAndDenylist() {
        ChannelDecisionResult lowCapital = service.evaluateOpen(
                "goodpeer", 1_000L, 10L, true, "proposal-hash");
        assertThat(lowCapital.passed()).isFalse();

        ChannelDecisionResult denylisted = service.evaluateOpen(
                "badpeer", 10_000_000L, 10L, true, "proposal-hash");
        assertThat(denylisted.passed()).isFalse();
    }

    @Test
    void rebalanceRequiresDrainProfitAndFund() {
        LightningChannelGateway.ChannelSnapshot drained = new LightningChannelGateway.ChannelSnapshot(
                "txid:0", "peer", true, 1_000_000L, 100_000L, 900_000L, 0, true, 100L);
        ChannelDecisionResult pass = service.evaluateRebalance(drained, 1_000L, 50_000L);
        assertThat(pass.passed()).isTrue();

        LightningChannelGateway.ChannelSnapshot healthy = new LightningChannelGateway.ChannelSnapshot(
                "txid:0", "peer", true, 1_000_000L, 600_000L, 400_000L, 0, true, 100L);
        ChannelDecisionResult fail = service.evaluateRebalance(healthy, 1_000L, 50_000L);
        assertThat(fail.passed()).isFalse();
    }

    @Test
    void ppmActionableOnDrainAlert() {
        ChannelDecisionResult result = service.evaluatePpm(2_000L, true);
        assertThat(result.passed()).isTrue();
        assertThat(service.recommendedPpm(2_000L, true)).isEqualTo(5_000L);
    }

    @Test
    void openFailsClosedWhenChannelsMeshInjectRequired() {
        KfeChannelDecisionService gated = new KfeChannelDecisionService(
                quorumGateway,
                systemWalletService,
                jammingGuard,
                new com.kerosene.kfe.adapters.out.rail.channels.FailClosedChannelsMeshInjectGateway(),
                10_000_000L,
                50L,
                0.20d,
                5_000L,
                1_000L,
                "",
                true,
                true);
        ChannelDecisionResult result = gated.evaluateOpen(
                "goodpeer",
                10_000_000L,
                10L,
                true,
                "proposal-hash");
        assertThat(result.passed()).isFalse();
        assertThat(result.evaluations().stream()
                        .filter(e -> !e.pass())
                        .map(ChannelFlagEvaluation::reason)
                        .anyMatch(r -> r.contains("CHANNELS_MESH_INJECT_NOT_WIRED")))
                .isTrue();
    }
}
