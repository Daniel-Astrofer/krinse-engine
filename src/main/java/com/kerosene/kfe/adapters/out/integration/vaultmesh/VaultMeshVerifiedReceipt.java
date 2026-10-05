package com.kerosene.kfe.adapters.out.integration.vaultmesh;

import com.kerosene.common.vaultmesh.intent.VaultMeshReceipt;

import java.time.Instant;
import java.util.List;

/**
 * Cryptographically verified vault-mesh receipt with full audit trail.
 *
 * <p>Wraps the contract-level {@link VaultMeshReceipt} with additional verification
 * fields: constitution binding, epoch, threshold, participant identity, transcript
 * integrity, and optional Ed25519 signature.
 *
 * @param receiptVersion schema version used to interpret receipt fields and proof format
 * @param intentId stable settlement intent associated with the signed PSBT
 * @param sessionId mesh signing session that produced the receipt
 * @param proposalHash canonical payment proposal digest bound to the decision
 * @param unsignedTransactionHash digest of the unsigned transaction structure submitted for signing
 * @param signedPsbtHash digest of the signed PSBT returned by the mesh
 * @param constitutionHash digest identifying the authorized Vault group configuration
 * @param dayEpoch day epoch under which the signing session was authorized
 * @param threshold minimum signer count used for the signing decision
 * @param participantIds distinct Vault member identities recorded in the transcript
 * @param transcriptHash digest binding the distributed signing rounds
 * @param decision accepted, rejected, or fail-stop mesh decision
 * @param signatureProof signature or transcript proof supplied for receipt verification
 * @param issuedAt instant at which the mesh issued the receipt
 * @param expiresAt instant after which the receipt must no longer authorize use of its PSBT
 */
public record VaultMeshVerifiedReceipt(
        /** Schema version used to interpret the receipt fields and proof format. */
        int receiptVersion,
        /** Stable settlement intent to which the signed PSBT and transcript belong. */
        String intentId,
        /** Mesh signing session that produced the receipt. */
        String sessionId,
        /** Canonical payment proposal digest bound into the mesh decision. */
        String proposalHash,
        /** Digest of the unsigned transaction structure submitted for signing. */
        String unsignedTransactionHash,
        /** Digest of the signed PSBT returned by the mesh. */
        String signedPsbtHash,
        /** Constitution/configuration digest identifying the authorized Vault group. */
        String constitutionHash,
        /** Day epoch under which the signing session was authorized. */
        int dayEpoch,
        /** Minimum signer threshold used for the signing decision. */
        int threshold,
        /** Distinct Vault member identities recorded as participants in the transcript. */
        List<String> participantIds,
        /** Digest of the protocol transcript used to bind the distributed signing rounds. */
        String transcriptHash,
        /** Accepted, rejected, or fail-stop decision represented by the receipt. */
        VaultMeshReceipt.Status decision,
        /** Signature or transcript proof supplied for downstream receipt verification. */
        String signatureProof,
        /** Instant when this receipt was issued by the mesh. */
        Instant issuedAt,
        /** Instant after which the receipt must no longer authorize use of its signed PSBT. */
        Instant expiresAt
) {
    /**
     * Converts the verified receipt to the smaller shared settlement contract.
     * The signed PSBT digest is carried as the contract payload; rejected decisions receive a
     * generic verification-failure reason because the richer audit fields remain on this value.
     *
     * @return contract receipt carrying intent, decision, signed PSBT hash, and issue time
     */
    public VaultMeshReceipt toContractReceipt() {
        return new VaultMeshReceipt(
                intentId,
                decision,
                decision == VaultMeshReceipt.Status.ACCEPTED ? null : "VERIFICATION_FAILED",
                signedPsbtHash,
                issuedAt);
    }
}
