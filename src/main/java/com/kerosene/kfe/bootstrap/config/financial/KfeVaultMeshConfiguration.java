package com.kerosene.kfe.bootstrap.config.financial;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.kerosene.common.vaultmesh.intent.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.settlement.VaultMeshSettlementPort;

import java.time.Instant;

/**
 * Fallback when {@code kfe.vaultmesh.enabled} is false (no HTTP client bean).
 */
@Configuration
public class KfeVaultMeshConfiguration {

    /**
     * Registers a fail-closed settlement port when no VaultMesh transport implementation is present.
     *
     * @return port that rejects every intent with the MESH_DISABLED reason
     */
    @Bean
    @ConditionalOnMissingBean(VaultMeshSettlementPort.class)
    public VaultMeshSettlementPort kfeVaultMeshSettlementPort() {
        return intent -> new VaultMeshReceipt(
                intent == null ? null : intent.intentId(),
                VaultMeshReceipt.Status.REJECTED,
                "MESH_DISABLED",
                null,
                Instant.now());
    }
}
