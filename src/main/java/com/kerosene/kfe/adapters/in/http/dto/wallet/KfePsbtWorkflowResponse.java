package com.kerosene.kfe.adapters.in.http.dto.wallet;

import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfePsbtWorkflowStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Persisted lifecycle snapshot for a cold-wallet PSBT approval and broadcast workflow.
 * The response carries transaction data and hashes, but never exports private key material.
 * @param id workflow identifier
 * @param userId owner of the wallet workflow
 * @param walletId cold wallet whose UTXOs fund the PSBT
 * @param status current approval, signing, or broadcast state
 * @param psbt base64-encoded PSBT currently associated with the workflow
 * @param psbtHash digest of the unsigned PSBT accepted for approval
 * @param signedPsbtHash digest of the PSBT after signature collection
 * @param rawTxHash digest of the finalized raw transaction, when available
 * @param broadcastTxid transaction ID returned after broadcast
 * @param amountSats amount sent to the requested destination
 * @param feeSats transaction fee in satoshis
 * @param destinationAddress validated external destination address
 * @param inputs UTXO inputs reserved for this workflow
 * @param failureMessage safe description of a terminal or recoverable failure
 * @param createdAt workflow creation timestamp
 * @param updatedAt latest persisted workflow update timestamp
 * @param signedAt time when the required signatures were attached
 * @param broadcastAt time when the finalized transaction was broadcast
 */
public record KfePsbtWorkflowResponse(
        UUID id,
        Long userId,
        UUID walletId,
        KfePsbtWorkflowStatus status,
        String psbt,
        String psbtHash,
        String signedPsbtHash,
        String rawTxHash,
        String broadcastTxid,
        long amountSats,
        long feeSats,
        String destinationAddress,
        List<KfeColdWalletPsbtRequest.Input> inputs,
        String failureMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime signedAt,
        LocalDateTime broadcastAt) {
}
