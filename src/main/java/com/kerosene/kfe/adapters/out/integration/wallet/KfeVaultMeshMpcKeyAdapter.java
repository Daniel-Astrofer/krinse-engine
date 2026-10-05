package com.kerosene.kfe.adapters.out.integration.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.kerosene.common.financial.operations.FinancialMpcKeyPort;
import com.kerosene.common.vaultmesh.settlement.VaultMeshDepositInfo;
import com.kerosene.common.vaultmesh.settlement.VaultMeshSettlementPort;

import java.util.UUID;

/**
 * Real {@link FinancialMpcKeyPort} backed by the vault mesh.
 *
 * <p>Custody in the vault-mesh model is <b>per-bucket</b>, not per-wallet: all USERS
 * custodial-onchain funds are held by a single shared Taproot group key
 * ({@code tr()} / {@code tb1p}) whose FROST shares live in the vault mesh. There is no
 * per-wallet MPC keygen. Therefore {@code keygenWallet} does not mint a fresh key; it
 * resolves the shared USERS bucket Taproot public key from the mesh
 * ({@code GET /v1/bitcoin/deposit?bucket=USERS}) and returns it as the wallet's
 * {@code mpcPublicKey}.
 *
 * <p>Downstream the stored value is only used as a presence flag
 * ({@code mpcKeyConfigured}); the actual signing path goes through
 * {@link VaultMeshSettlementPort#signPsbt} against the same shared key. Returning the
 * bucket shared key keeps wallet creation consistent with custody reality.
 *
 * <p>Active only when {@code kfe.vaultmesh.enabled=true}; otherwise the dev fallback in
 * {@code KfeFinancialFallbackConfiguration} (which throws / dev-escapes) wins. Fails
 * closed: if the mesh cannot provide the USERS deposit key, wallet creation must not
 * proceed — the caller marks the wallet {@code KEYGEN_FAILED}.
 */
@Component
@ConditionalOnProperty(name = "kfe.vaultmesh.enabled", havingValue = "true")
public class KfeVaultMeshMpcKeyAdapter implements FinancialMpcKeyPort {

    /** Logger used when a valid shared USERS bucket key is assigned to a newly provisioned wallet. */
    private static final Logger log = LoggerFactory.getLogger(KfeVaultMeshMpcKeyAdapter.class);

    /** Lazy settlement port used to retrieve the mesh's shared Taproot USERS public key. */
    private final ObjectProvider<VaultMeshSettlementPort> settlementPort;

    /** @param settlementPort lazy Vault mesh port provider used during wallet key provisioning */
    public KfeVaultMeshMpcKeyAdapter(ObjectProvider<VaultMeshSettlementPort> settlementPort) {
        this.settlementPort = settlementPort;
    }

    /**
     * Returns the shared USERS bucket Taproot public key; wallet-specific key generation is not
     * performed because custody keys belong to the bucket, not individual wallets. Missing mesh,
     * deposit data, or a usable public key prevents wallet provisioning.
     *
     * @param walletId wallet being provisioned; included in the operational audit log
     * @param userId wallet owner; included in the operational audit log
     * @return normalized 32-byte x-only public key as lowercase hexadecimal text
     * @throws IllegalStateException when the mesh port, USERS deposit record, or usable key is absent
     */
    @Override
    public String keygenWallet(UUID walletId, Long userId) {
        VaultMeshSettlementPort port = settlementPort.getIfAvailable();
        if (port == null) {
            throw new IllegalStateException(
                    "Vault mesh settlement port unavailable; cannot provision MPC public key.");
        }
        VaultMeshDepositInfo deposit = port.getUsersDepositAddress();
        if (deposit == null) {
            throw new IllegalStateException(
                    "Vault mesh USERS deposit key unavailable; cannot provision MPC public key.");
        }
        String publicKey = resolvePublicKey(deposit);
        if (publicKey == null || publicKey.isBlank()) {
            throw new IllegalStateException(
                    "Vault mesh USERS deposit key returned an empty public key.");
        }
        log.info(
                "[KFE MPC] Provisioned bucket-shared Taproot public key for walletId={} userId={}",
                walletId,
                userId);
        return publicKey;
    }

    /**
     * Resolve the x-only Taproot internal key from the deposit info.
     *
     * <p>Requires {@code xonlyPubkeyHex} as a 32-byte x-only public key (exactly 64 hex chars).
     * Falls back to {@code outputPubkeyHex} only if it's also a valid 64-char hex key.
     * NEVER uses the on-chain address as a public key — they are different types.
     */
    private String resolvePublicKey(VaultMeshDepositInfo deposit) {
        String xonly = deposit.xonlyPubkeyHex();
        if (xonly != null && !xonly.isBlank() && xonly.trim().matches("^[0-9a-fA-F]{64}$")) {
            return xonly.trim().toLowerCase(java.util.Locale.ROOT);
        }
        String output = deposit.outputPubkeyHex();
        if (output != null && !output.isBlank() && output.trim().matches("^[0-9a-fA-F]{64}$")) {
            log.warn("[KFE MPC] Using outputPubkeyHex as fallback for xonly pubkey; "
                    + "mesh should provide xonly_pubkey field");
            return output.trim().toLowerCase(java.util.Locale.ROOT);
        }
        return null;
    }
}
