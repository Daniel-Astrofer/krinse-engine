package com.kerosene.kfe.bootstrap.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;
import com.kerosene.kfe.adapters.in.http.KfeHealthController;
import com.kerosene.kfe.wallet.adapters.out.persistence.KfeSystemWalletService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class KfeStartupReadinessLifecycleTest {
    @Test
    void realBootRunnerKeepsReadinessClosedWhileRetryingAndOpensOnlyAfterCompletion() {
        var application = new SpringApplication(BootFixture.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        application.setLogStartupInfo(false);
        application.setDefaultProperties(Map.ofEntries(
                Map.entry("kfe.standalone", "true"), Map.entry("spring.main.banner-mode", "off"),
                Map.entry("bitcoin.rpc.enabled", "true"), Map.entry("bitcoin.rpc.required", "true"),
                Map.entry("bitcoin.network", "testnet3"), Map.entry("bitcoin.rpc.wallet", "fixture-primary"),
                Map.entry("kfe.bitcoin-core.wallets.funds", "fixture-funds"),
                Map.entry("kfe.bitcoin-core.wallets.profit", "fixture-profit"),
                Map.entry("kfe.bitcoin-core.wallets.bootstrap-enabled", "true"),
                Map.entry("kfe.bitcoin.validate-network-enabled", "true"),
                Map.entry("kfe.bitcoin.require-synced-enabled", "true"),
                Map.entry("kfe.bitcoin.startup.max-attempts", "3"),
                Map.entry("kfe.bitcoin.startup.retry-delay-ms", "0")));

        try (var context = application.run()) {
            var health = context.getBean(KfeHealthController.class);
            var bitcoin = context.getBean(BitcoinCoreRpcClient.class);
            var systemWallets = context.getBean(KfeSystemWalletService.class);
            assertThat(health.ready().getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(bitcoin, times(2)).blockchainInfo();
            verify(bitcoin, never()).chain();
            verify(bitcoin, times(3)).ensureWalletLoaded(anyString());
            verify(systemWallets).ensureSystemWallets();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ApplicationAvailabilityAutoConfiguration.class, KfeHealthController.class,
            KfeBitcoinRuntimeBootstrap.class})
    static class BootFixture {
        @Bean
        KfeSystemWalletService systemWalletService() {
            var service = mock(KfeSystemWalletService.class);
            when(service.ensureSystemWallets()).thenReturn(new KfeSystemWalletService.SystemWallets(
                    new UUID(0, 1), new UUID(0, 2)));
            return service;
        }

        @Bean
        BitcoinCoreRpcClient bitcoinCore(KfeHealthController health) {
            var bitcoin = mock(BitcoinCoreRpcClient.class);
            var reads = new AtomicInteger();
            when(bitcoin.blockchainInfo()).thenAnswer(invocation -> {
                assertThat(health.live().status()).isEqualTo("UP");
                assertThat(health.ready().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(health.ready().getBody().dependencies())
                        .containsEntry("application", "REFUSING_TRAFFIC");
                verify(bitcoin, never()).ensureWalletLoaded(anyString());
                if (reads.getAndIncrement() == 0) {
                    throw new IllegalStateException("fixture wrapper", new ResourceAccessException(
                            "fixture startup connection", new ConnectException("fixture refused")));
                }
                return new ObjectMapper().createObjectNode().put("chain", "test")
                        .put("initialblockdownload", false);
            });
            return bitcoin;
        }
    }
}
