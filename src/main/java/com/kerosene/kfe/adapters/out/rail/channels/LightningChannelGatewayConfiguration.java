package com.kerosene.kfe.adapters.out.rail.channels;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ensures a {@link LightningChannelGateway} always exists.
 * When LND REST is enabled, {@link com.kerosene.kfe.adapters.out.rail.lightning.LndRestLightningClient} supplies the bean;
 * otherwise a fail-closed disabled gateway is registered.
 *
 * <p>CHANNELS→LND mesh inject defaults to {@link FailClosedChannelsMeshInjectGateway}
 * when vaultmesh is off. With {@code kfe.vaultmesh.enabled=true},
 * {@link VaultMeshChannelsMeshInjectGateway} soft-reserves CHANNELS capital, funds LND
 * on-chain from the dedicated CHANNELS Taproot key (≠ USERS), then commits after open.
 */
@Configuration
public class LightningChannelGatewayConfiguration {

    /**
     * Creates a disabled Lightning gateway only when no functional implementation is registered.
     *
     * @return fail-closed gateway for installations without an LND REST client
     */
    @Bean
    @ConditionalOnMissingBean(LightningChannelGateway.class)
    public LightningChannelGateway disabledLightningChannelGateway() {
        return new DisabledLightningChannelGateway();
    }

    /**
     * Creates the fail-closed CHANNELS injection gateway when no VaultMesh implementation exists.
     *
     * @return gateway that refuses reserve, funding, release, and commit operations
     */
    @Bean
    @ConditionalOnMissingBean(ChannelsMeshInjectGateway.class)
    public ChannelsMeshInjectGateway failClosedChannelsMeshInjectGateway() {
        return new FailClosedChannelsMeshInjectGateway();
    }
}
