package com.kerosene.kfe.adapters.out.rail.channels;

import com.kerosene.common.vaultmesh.settlement.VaultMeshDepositInfo;
import com.kerosene.common.vaultmesh.intent.VaultMeshIntent;
import com.kerosene.common.vaultmesh.settlement.VaultMeshPsbtReceipt;
import com.kerosene.common.vaultmesh.settlement.VaultMeshPsbtRequest;
import com.kerosene.common.vaultmesh.intent.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.settlement.VaultMeshSettlementPort;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VaultMeshChannelsMeshInjectGatewayTest {

    @Test
    void authorizeOpenIsNonMutatingReadinessCheck() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        VaultMeshChannelsMeshInjectGateway gateway = gateway(port, null);

        assertThat(gateway.authorizeOpen(0L, "02ab").authorized()).isFalse();
        assertThat(gateway.authorizeOpen(1L, " ").authorized()).isFalse();
        assertThat(gateway.authorizeOpen(1_000L, "02abcd").authorized()).isTrue();
        assertThat(gateway.authorizeOpen(1_000L, "02abcd").reasonCode())
                .contains("CHANNELS_INJECT_READY");
        verify(port, never()).reserveIntent(any());
        verify(port, never()).submitIntent(any());
    }

    @Test
    void reserveOpenAcceptsWhenVaultReceiptAccepted() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        VaultMeshChannelsMeshInjectGateway gateway = gateway(port, null);

        when(port.reserveIntent(
                        argThat(
                                (VaultMeshIntent i) ->
                                        i.bucket().equals("CHANNELS")
                                                && i.amountSats() == 12_345L
                                                && i.destination()
                                                        .equals(
                                                                VaultMeshChannelsMeshInjectGateway
                                                                        .CHANNELS_DESTINATION)
                                                && i.intentId().equals("channels-inject-open-1")
                                                && i.policyHash().isEmpty())))
                .thenReturn(
                        new VaultMeshReceipt(
                                "channels-inject-open-1",
                                VaultMeshReceipt.Status.ACCEPTED,
                                "RESERVED",
                                null,
                                Instant.now()));

        ChannelsMeshInjectGateway.DebitResult r =
                gateway.reserveOpen("channels-inject-open-1", 12_345L, "02ABCD");
        assertThat(r.authorized()).isTrue();
        assertThat(r.intentId()).isEqualTo("channels-inject-open-1");
        assertThat(r.reasonCode().toUpperCase(Locale.ROOT)).contains("CHANNELS_INJECT_RESERVED");
    }

    @Test
    void reserveOpenRefusesWhenVaultReceiptRejected() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        VaultMeshChannelsMeshInjectGateway gateway = gateway(port, null);

        when(port.reserveIntent(argThat(i -> i.bucket().equals("CHANNELS"))))
                .thenReturn(
                        new VaultMeshReceipt(
                                "intent-123",
                                VaultMeshReceipt.Status.REJECTED,
                                "NO_CAPITAL",
                                null,
                                Instant.now()));

        ChannelsMeshInjectGateway.DebitResult r =
                gateway.reserveOpen("intent-123", 1_000L, "02abcd");
        assertThat(r.authorized()).isFalse();
        assertThat(r.reasonCode().toUpperCase(Locale.ROOT)).contains("CHANNELS_INJECT_RESERVE_REJECTED");
    }

    @Test
    void releaseAndCommitDelegateToSettlementPort() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        VaultMeshChannelsMeshInjectGateway gateway = gateway(port, null);

        when(port.releaseIntent(eq("intent-1"), eq("CHANNELS"), eq(500L)))
                .thenReturn(
                        new VaultMeshReceipt(
                                "intent-1",
                                VaultMeshReceipt.Status.ACCEPTED,
                                "RELEASED",
                                null,
                                Instant.now()));
        when(port.commitIntent(eq("intent-1")))
                .thenReturn(
                        new VaultMeshReceipt(
                                "intent-1",
                                VaultMeshReceipt.Status.ACCEPTED,
                                "COMMITTED",
                                null,
                                Instant.now()));

        assertThat(gateway.releaseOpen("intent-1", 500L, "02ab").authorized()).isTrue();
        assertThat(gateway.commitOpen("intent-1").authorized()).isTrue();
        verify(port).releaseIntent("intent-1", "CHANNELS", 500L);
        verify(port).commitIntent("intent-1");
        verify(port, never()).submitIntent(any());
    }

    @Test
    void fundOpenFailClosedWithoutBitcoinCoreOrInvalidAddress() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        VaultMeshChannelsMeshInjectGateway gateway = gateway(port, null);

        assertThat(gateway.fundOpen("intent-1", 100L, " ").authorized()).isFalse();
        assertThat(gateway.fundOpen("intent-1", 100L, "not-an-address").authorized()).isFalse();
        assertThat(
                        gateway
                                .fundOpen(
                                        "intent-1",
                                        100L,
                                        "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx")
                                .reasonCode())
                .contains("CHANNELS_INJECT_FUND_NO_BITCOIN_CORE");
        verify(port, never()).signPsbt(any());
    }

    @Test
    void fundOpenBuildsSignsAndBroadcastsChannelsPsbt() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        BitcoinCoreRpcClient core = mock(BitcoinCoreRpcClient.class);
        VaultMeshChannelsMeshInjectGateway gateway = gateway(port, core);

        when(port.getChannelsDepositAddress())
                .thenReturn(
                        new VaultMeshDepositInfo(
                                "tb1pchannels0000000000000000000000000000000000000000000000000000",
                                "tr(channelskey)",
                                "frost-secp256k1-tr-v3",
                                "cc",
                                "dd",
                                "testnet3"));
        when(port.getUsersDepositAddress())
                .thenReturn(
                        new VaultMeshDepositInfo(
                                "tb1pusers000000000000000000000000000000000000000000000000000000",
                                "tr(userskey)",
                                "frost-secp256k1-tr-v3",
                                "aa",
                                "bb",
                                "testnet3"));
        when(core.createFundedPsbt(any(), eq(100L), eq(1), eq(null), eq("bech32m")))
                .thenReturn(new BitcoinCoreRpcClient.FundedPsbt("funded-psbt", 250L));
        when(port.signPsbt(any(VaultMeshPsbtRequest.class)))
                .thenReturn(
                        new VaultMeshPsbtReceipt(
                                "intent-1",
                                VaultMeshReceipt.Status.ACCEPTED,
                                null,
                                "signed-psbt",
                                "proof",
                                Instant.now()));
        when(core.finalizePsbt("signed-psbt"))
                .thenReturn(new BitcoinCoreRpcClient.FinalizedPsbt("deadbeef", true));
        when(core.sendRawTransaction("deadbeef")).thenReturn("broadcasttxid");

        ChannelsMeshInjectGateway.FundResult ok =
                gateway.fundOpen(
                        "intent-1", 100L, "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx");

        assertThat(ok.authorized()).isTrue();
        assertThat(ok.fundingTxid()).isEqualTo("broadcasttxid");
        assertThat(ok.reasonCode().toUpperCase(Locale.ROOT)).contains("CHANNELS_INJECT_FUNDED_ONCHAIN");
        verify(port)
                .signPsbt(
                        argThat(
                                (VaultMeshPsbtRequest r) ->
                                        "CHANNELS".equals(r.bucket())
                                                && !r.shouldCommitIntent()
                                                && "funded-psbt".equals(r.psbtBase64())));
        verify(core).sendRawTransaction("deadbeef");
    }

    @Test
    void fundOpenRefusesUsersChannelsKeyCollision() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        BitcoinCoreRpcClient core = mock(BitcoinCoreRpcClient.class);
        VaultMeshChannelsMeshInjectGateway gateway = gateway(port, core);
        VaultMeshDepositInfo same =
                new VaultMeshDepositInfo(
                        "tb1psame0000000000000000000000000000000000000000000000000000000",
                        "tr(x)",
                        "frost-secp256k1-tr-v3",
                        "aa",
                        "bb",
                        "testnet3");
        when(port.getChannelsDepositAddress()).thenReturn(same);
        when(port.getUsersDepositAddress()).thenReturn(same);

        ChannelsMeshInjectGateway.FundResult r =
                gateway.fundOpen(
                        "intent-1", 100L, "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx");
        assertThat(r.authorized()).isFalse();
        assertThat(r.reasonCode()).contains("CHANNELS_INJECT_FUND_KEY_COLLISION_USERS");
        verify(port, never()).signPsbt(any());
    }

    @Test
    void reserveAndCommitAreIdempotentOnReplayReasons() {
        VaultMeshSettlementPort port = mock(VaultMeshSettlementPort.class);
        VaultMeshChannelsMeshInjectGateway gateway = gateway(port, null);

        when(port.reserveIntent(any()))
                .thenReturn(
                        new VaultMeshReceipt(
                                "intent-1",
                                VaultMeshReceipt.Status.REJECTED,
                                "intent replay: intent-1",
                                null,
                                Instant.now()));
        when(port.commitIntent(eq("intent-1")))
                .thenReturn(
                        new VaultMeshReceipt(
                                "intent-1",
                                VaultMeshReceipt.Status.REJECTED,
                                "intent replay: intent-1",
                                null,
                                Instant.now()));

        assertThat(gateway.reserveOpen("intent-1", 100L, "02ab").authorized()).isTrue();
        assertThat(gateway.commitOpen("intent-1").authorized()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static VaultMeshChannelsMeshInjectGateway gateway(
            VaultMeshSettlementPort port, BitcoinCoreRpcClient core) {
        ObjectProvider<BitcoinCoreRpcClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(core);
        return new VaultMeshChannelsMeshInjectGateway(port, provider, 1, 50_000L, 0L);
    }
}
