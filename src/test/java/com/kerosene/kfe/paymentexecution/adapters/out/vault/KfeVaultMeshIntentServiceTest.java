package com.kerosene.kfe.paymentexecution.adapters.out.vault;

import org.junit.jupiter.api.Test;
import com.kerosene.common.vaultmesh.intent.VaultMeshIntent;
import com.kerosene.common.vaultmesh.intent.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.settlement.VaultMeshSettlementPort;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class KfeVaultMeshIntentServiceTest {

    @Test
    void submitOutboundIntentBuildsUsersBucketIntent() {
        AtomicReference<VaultMeshIntent> seen = new AtomicReference<>();
        VaultMeshSettlementPort port = intent -> {
            seen.set(intent);
            return new VaultMeshReceipt(
                    intent.intentId(),
                    VaultMeshReceipt.Status.ACCEPTED,
                    null,
                    "proof",
                    Instant.ofEpochMilli(1L));
        };
        KfeVaultMeshIntentService service = new KfeVaultMeshIntentService(port, false, false, "USERS");
        UUID txId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        VaultMeshReceipt receipt = service.submitOutboundIntent(txId, "bc1qdest", 99L, "policy");

        assertThat(receipt.status()).isEqualTo(VaultMeshReceipt.Status.ACCEPTED);
        assertThat(seen.get().intentId()).isEqualTo(txId.toString());
        assertThat(seen.get().bucket()).isEqualTo("USERS");
        assertThat(seen.get().destination()).isEqualTo("bc1qdest");
        assertThat(seen.get().amountSats()).isEqualTo(99L);
        assertThat(seen.get().policyHash()).isEqualTo("policy");
        assertThat(service.isSubmitOnOutboundEnabled()).isFalse();
    }
}
