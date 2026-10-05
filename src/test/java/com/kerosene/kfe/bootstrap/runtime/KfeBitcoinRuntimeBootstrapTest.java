package com.kerosene.kfe.bootstrap.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;
import com.kerosene.kfe.wallet.adapters.out.persistence.KfeSystemWalletService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class KfeBitcoinRuntimeBootstrapTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void validatesBitcoinCoreNetworkAndLoadsConfiguredWalletsFromOneSnapshot() {
        Fixture fixture = new Fixture();
        when(fixture.bitcoinCore.blockchainInfo()).thenReturn(blockchainInfo("test", false));

        fixture.bootstrap(true, true, true, false, true, "testnet").run(null);

        InOrder order = inOrder(fixture.systemWalletService, fixture.bitcoinCore);
        order.verify(fixture.systemWalletService).ensureSystemWallets();
        order.verify(fixture.bitcoinCore).blockchainInfo();
        order.verify(fixture.bitcoinCore).ensureWalletLoaded("kerosene");
        order.verify(fixture.bitcoinCore).ensureWalletLoaded("kerosene-funds");
        order.verify(fixture.bitcoinCore).ensureWalletLoaded("kerosene-profit");
        verifyNoMoreInteractions(fixture.systemWalletService, fixture.bitcoinCore);
        assertThat(fixture.retryDelays).isEmpty();
    }

    @Test
    void retriesOnlyReadOnlySnapshotAndCreatesSystemAndRpcWalletsOnceAfterRecovery() {
        Fixture fixture = new Fixture();
        when(fixture.bitcoinCore.blockchainInfo())
                .thenThrow(connectionFailure())
                .thenThrow(connectionFailure())
                .thenReturn(blockchainInfo("testnet4", false));

        fixture.bootstrap(true, true, true, true, true, "testnet4").run(null);

        InOrder order = inOrder(fixture.systemWalletService, fixture.bitcoinCore);
        order.verify(fixture.systemWalletService).ensureSystemWallets();
        order.verify(fixture.bitcoinCore, times(3)).blockchainInfo();
        order.verify(fixture.bitcoinCore).ensureWalletLoaded("kerosene");
        order.verify(fixture.bitcoinCore).ensureWalletLoaded("kerosene-funds");
        order.verify(fixture.bitcoinCore).ensureWalletLoaded("kerosene-profit");
        verifyNoMoreInteractions(fixture.systemWalletService, fixture.bitcoinCore);
        assertThat(fixture.retryDelays).containsExactly(2L, 2L);
    }

    @Test
    void exhaustedTransientFailuresDoNotReachRpcWalletOperations() {
        Fixture fixture = new Fixture();
        when(fixture.bitcoinCore.blockchainInfo()).thenThrow(connectionFailure());

        assertThatThrownBy(() -> fixture.bootstrap(true, true, true, false, true, "testnet4").run(null))
                .isInstanceOf(IllegalStateException.class);

        verify(fixture.systemWalletService).ensureSystemWallets();
        verify(fixture.bitcoinCore, times(3)).blockchainInfo();
        verify(fixture.bitcoinCore, never()).ensureWalletLoaded(anyString());
        verify(fixture.bitcoinCore, never()).chain();
        verifyNoMoreInteractions(fixture.systemWalletService, fixture.bitcoinCore);
        assertThat(fixture.retryDelays).containsExactly(2L, 2L);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void rejectsPermanentAuthenticationFailuresWithoutRetryOrRpcWalletOperations(int status) {
        Fixture fixture = new Fixture();
        when(fixture.bitcoinCore.blockchainInfo()).thenThrow(new IllegalStateException(
                "Synthetic RPC wrapper", new HttpClientErrorException(HttpStatus.valueOf(status))));

        assertThatThrownBy(() -> fixture.bootstrap(true, true, true, false, true, "testnet4").run(null))
                .isInstanceOf(IllegalStateException.class);

        fixture.verifyOneReadAndNoRpcWallets();
        assertThat(fixture.retryDelays).isEmpty();
    }

    @Test
    void rejectsMismatchedBitcoinCoreNetworkWithoutRetryOrRpcWalletOperations() {
        Fixture fixture = new Fixture();
        when(fixture.bitcoinCore.blockchainInfo()).thenReturn(blockchainInfo("main", false));

        assertThatThrownBy(() -> fixture.bootstrap(true, true, true, false, true, "testnet").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bitcoin Core chain mismatch");

        fixture.verifyOneReadAndNoRpcWallets();
        assertThat(fixture.retryDelays).isEmpty();
    }

    @Test
    void chainMismatchDoesNotExposeRemoteChainText() {
        Fixture fixture = new Fixture();
        String untrustedChain = "synthetic-sensitive-remote-chain";
        when(fixture.bitcoinCore.blockchainInfo()).thenReturn(blockchainInfo(untrustedChain, false));

        assertThatThrownBy(() -> fixture.bootstrap(true, true, true, false, true, "testnet4").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bitcoin Core chain mismatch")
                .hasMessageNotContaining(untrustedChain);

        fixture.verifyOneReadAndNoRpcWallets();
        assertThat(fixture.retryDelays).isEmpty();
    }

    @Test
    void rejectsUnsupportedConfiguredNetworkBeforeReadOrRpcWalletOperations() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.bootstrap(true, true, true, false, true, "unknown-network").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported bitcoin.network");

        verify(fixture.systemWalletService).ensureSystemWallets();
        verifyNoMoreInteractions(fixture.systemWalletService);
        verifyNoInteractions(fixture.bitcoinCore);
        assertThat(fixture.retryDelays).isEmpty();
    }

    @Test
    void rejectsUnsyncedBitcoinCoreWhenSyncIsRequiredWithoutRetryOrRpcWalletOperations() {
        Fixture fixture = new Fixture();
        when(fixture.bitcoinCore.blockchainInfo()).thenReturn(blockchainInfo("testnet4", true));

        assertThatThrownBy(() -> fixture.bootstrap(true, true, true, true, true, "testnet4").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("initial block download");

        fixture.verifyOneReadAndNoRpcWallets();
        assertThat(fixture.retryDelays).isEmpty();
    }

    @Test
    void acceptsUnsyncedBitcoinCoreWhenSynchronizationIsNotRequired() {
        Fixture fixture = new Fixture();
        when(fixture.bitcoinCore.blockchainInfo()).thenReturn(blockchainInfo("testnet4", true));

        assertThatCode(() -> fixture.bootstrap(true, true, true, false, true, "testnet4").run(null))
                .doesNotThrowAnyException();

        verify(fixture.systemWalletService).ensureSystemWallets();
        verify(fixture.bitcoinCore).blockchainInfo();
        verify(fixture.bitcoinCore).ensureWalletLoaded("kerosene");
        verify(fixture.bitcoinCore).ensureWalletLoaded("kerosene-funds");
        verify(fixture.bitcoinCore).ensureWalletLoaded("kerosene-profit");
        verifyNoMoreInteractions(fixture.systemWalletService, fixture.bitcoinCore);
        assertThat(fixture.retryDelays).isEmpty();
    }

    @ParameterizedTest(name = "rejects malformed blockchain snapshot: {0}")
    @MethodSource("malformedSnapshots")
    void rejectsMalformedSnapshotWithoutRetryOrRpcWalletOperations(String description, JsonNode snapshot) {
        Fixture fixture = new Fixture();
        when(fixture.bitcoinCore.blockchainInfo()).thenReturn(snapshot);

        assertThatThrownBy(() -> fixture.bootstrap(true, true, true, false, true, "testnet4").run(null))
                .isInstanceOf(IllegalStateException.class);

        fixture.verifyOneReadAndNoRpcWallets();
        assertThat(fixture.retryDelays).isEmpty();
    }

    @Test
    void networkValidationCanBeDisabledWithoutDisablingRequiredSyncValidation() {
        Fixture fixture = new Fixture();
        when(fixture.bitcoinCore.blockchainInfo()).thenReturn(blockchainInfo("main", true));

        assertThatThrownBy(() -> fixture.bootstrap(true, true, false, true, true, "testnet4").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("initial block download");

        fixture.verifyOneReadAndNoRpcWallets();
        assertThat(fixture.retryDelays).isEmpty();
    }

    @Test
    void disablingNetworkComparisonDoesNotPermitMalformedSnapshot() {
        Fixture fixture = new Fixture();
        when(fixture.bitcoinCore.blockchainInfo()).thenReturn(OBJECT_MAPPER.createObjectNode()
                .put("chain", "testnet4").put("initialblockdownload", "false"));

        assertThatThrownBy(() -> fixture.bootstrap(true, true, false, false, true, "testnet4").run(null))
                .isInstanceOf(IllegalStateException.class);

        fixture.verifyOneReadAndNoRpcWallets();
        assertThat(fixture.retryDelays).isEmpty();
    }

    @Test
    void skipsRpcWhenDisabledButEnsuresSystemWalletsOnce() {
        Fixture fixture = new Fixture();

        fixture.bootstrap(false, true, true, true, true, "testnet4").run(null);

        verify(fixture.systemWalletService).ensureSystemWallets();
        verifyNoMoreInteractions(fixture.systemWalletService);
        verifyNoInteractions(fixture.bitcoinCore);
        assertThat(fixture.retryDelays).isEmpty();
    }

    @Test
    void skipsMissingOptionalClientButEnsuresSystemWalletsOnce() {
        Fixture fixture = new Fixture();

        fixture.bootstrapWithClient(null, true, false).run(null);

        verify(fixture.systemWalletService).ensureSystemWallets();
        verifyNoMoreInteractions(fixture.systemWalletService);
        verifyNoInteractions(fixture.bitcoinCore);
        assertThat(fixture.retryDelays).isEmpty();
    }

    @Test
    void rejectsMissingRequiredClientWithoutRetry() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.bootstrapWithClient(null, true, true).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client is unavailable");

        verify(fixture.systemWalletService).ensureSystemWallets();
        verifyNoMoreInteractions(fixture.systemWalletService);
        verifyNoInteractions(fixture.bitcoinCore);
        assertThat(fixture.retryDelays).isEmpty();
    }

    @Test
    void validatesSnapshotWithoutLoadingWalletsWhenRpcWalletBootstrapIsDisabled() {
        Fixture fixture = new Fixture();
        when(fixture.bitcoinCore.blockchainInfo()).thenReturn(blockchainInfo("testnet4", false));

        fixture.bootstrap(true, true, true, true, false, "testnet4").run(null);

        fixture.verifyOneReadAndNoRpcWallets();
        assertThat(fixture.retryDelays).isEmpty();
    }

    @Test
    void neverRepeatsSnapshotOrWalletOperationsWhenWalletLoadFailsWithTransientTransportError() {
        Fixture fixture = new Fixture();
        IllegalStateException walletFailure = connectionFailure();
        when(fixture.bitcoinCore.blockchainInfo()).thenReturn(blockchainInfo("testnet4", false));
        doThrow(walletFailure).when(fixture.bitcoinCore).ensureWalletLoaded("kerosene-funds");

        assertThatThrownBy(() -> fixture.bootstrap(true, true, true, true, true, "testnet4").run(null))
                .isSameAs(walletFailure);

        InOrder order = inOrder(fixture.systemWalletService, fixture.bitcoinCore);
        order.verify(fixture.systemWalletService).ensureSystemWallets();
        order.verify(fixture.bitcoinCore).blockchainInfo();
        order.verify(fixture.bitcoinCore).ensureWalletLoaded("kerosene");
        order.verify(fixture.bitcoinCore).ensureWalletLoaded("kerosene-funds");
        verify(fixture.bitcoinCore, never()).ensureWalletLoaded("kerosene-profit");
        verifyNoMoreInteractions(fixture.systemWalletService, fixture.bitcoinCore);
        assertThat(fixture.retryDelays).isEmpty();
    }

    @Test
    void neverRepeatsLocalSystemWalletInitializationWhenItFails() {
        Fixture fixture = new Fixture();
        IllegalStateException walletFailure = new IllegalStateException("Synthetic persistence failure");
        when(fixture.systemWalletService.ensureSystemWallets()).thenThrow(walletFailure);

        assertThatThrownBy(() -> fixture.bootstrap(true, true, true, true, true, "testnet4").run(null))
                .isSameAs(walletFailure);

        verify(fixture.systemWalletService).ensureSystemWallets();
        verifyNoMoreInteractions(fixture.systemWalletService);
        verifyNoInteractions(fixture.bitcoinCore);
        assertThat(fixture.retryDelays).isEmpty();
    }

    private static Stream<Arguments> malformedSnapshots() {
        return Stream.of(
                Arguments.of("null response", (JsonNode) null),
                Arguments.of("JSON null", OBJECT_MAPPER.nullNode()),
                Arguments.of("array", OBJECT_MAPPER.createArrayNode()),
                Arguments.of("text", OBJECT_MAPPER.getNodeFactory().textNode("unexpected")),
                Arguments.of("empty object", OBJECT_MAPPER.createObjectNode()),
                Arguments.of("missing chain", OBJECT_MAPPER.createObjectNode().put("initialblockdownload", false)),
                Arguments.of("numeric chain", OBJECT_MAPPER.createObjectNode().put("chain", 7).put("initialblockdownload", false)),
                Arguments.of("empty chain", blockchainInfo("", false)),
                Arguments.of("blank chain", blockchainInfo("  ", false)),
                Arguments.of("missing IBD", OBJECT_MAPPER.createObjectNode().put("chain", "testnet4")),
                Arguments.of("null IBD", OBJECT_MAPPER.createObjectNode().put("chain", "testnet4").putNull("initialblockdownload")),
                Arguments.of("text IBD", OBJECT_MAPPER.createObjectNode().put("chain", "testnet4").put("initialblockdownload", "false")),
                Arguments.of("numeric IBD", OBJECT_MAPPER.createObjectNode().put("chain", "testnet4").put("initialblockdownload", 0)));
    }

    private static IllegalStateException connectionFailure() {
        return new IllegalStateException("Synthetic RPC wrapper", new ResourceAccessException(
                "Synthetic transport failure", new ConnectException("Synthetic connection refused")));
    }

    private static JsonNode blockchainInfo(String chain, boolean initialBlockDownload) {
        return OBJECT_MAPPER.createObjectNode()
                .put("chain", chain)
                .put("blocks", 56_000L)
                .put("headers", 142_000L)
                .put("verificationprogress", 0.64D)
                .put("initialblockdownload", initialBlockDownload);
    }

    private static final class Fixture {
        private final KfeSystemWalletService systemWalletService = mock(KfeSystemWalletService.class);
        private final BitcoinCoreRpcClient bitcoinCore = mock(BitcoinCoreRpcClient.class);
        private final List<Long> retryDelays = new ArrayList<>();
        private long elapsedNanos;
        private final KfeBitcoinStartupProbe startupProbe = new KfeBitcoinStartupProbe(
                3, 2L, 60_000L,
                delayMs -> {
                    retryDelays.add(delayMs);
                    elapsedNanos += TimeUnit.MILLISECONDS.toNanos(delayMs);
                },
                () -> elapsedNanos);

        private Fixture() {
            when(systemWalletService.ensureSystemWallets())
                    .thenReturn(new KfeSystemWalletService.SystemWallets(UUID.randomUUID(), UUID.randomUUID()));
        }

        private KfeBitcoinRuntimeBootstrap bootstrap(
                boolean enabled, boolean required, boolean validateNetwork, boolean requireSynced,
                boolean bootstrapWallets, String configuredNetwork) {
            return new KfeBitcoinRuntimeBootstrap(
                    systemWalletService, provider(bitcoinCore), enabled, required, validateNetwork,
                    requireSynced, bootstrapWallets, configuredNetwork, "kerosene", "kerosene-funds",
                    "kerosene-profit", startupProbe);
        }

        private KfeBitcoinRuntimeBootstrap bootstrapWithClient(
                BitcoinCoreRpcClient client, boolean enabled, boolean required) {
            return new KfeBitcoinRuntimeBootstrap(
                    systemWalletService, provider(client), enabled, required, true, false, true,
                    "testnet4", "kerosene", "kerosene-funds", "kerosene-profit", startupProbe);
        }

        private void verifyOneReadAndNoRpcWallets() {
            verify(systemWalletService).ensureSystemWallets();
            verify(bitcoinCore).blockchainInfo();
            verify(bitcoinCore, never()).chain();
            verify(bitcoinCore, never()).ensureWalletLoaded(anyString());
            verifyNoMoreInteractions(systemWalletService, bitcoinCore);
        }
    }

    private static ObjectProvider<BitcoinCoreRpcClient> provider(BitcoinCoreRpcClient bitcoinCore) {
        return new ObjectProvider<>() {
            @Override
            public BitcoinCoreRpcClient getObject(Object... args) {
                return bitcoinCore;
            }

            @Override
            public BitcoinCoreRpcClient getIfAvailable() {
                return bitcoinCore;
            }

            @Override
            public BitcoinCoreRpcClient getIfUnique() {
                return bitcoinCore;
            }

            @Override
            public BitcoinCoreRpcClient getObject() {
                return bitcoinCore;
            }
        };
    }
}
