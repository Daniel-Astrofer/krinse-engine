package com.kerosene.kfe.adapters.in.http.dto.wallet;

import java.util.List;
import java.util.UUID;

/** Client response containing an unsigned cold-wallet PSBT and the data needed to review it offline.
 * The serialized PSBT is paired with a digest and transaction summary so a signer can verify
 * the intended transfer before returning signatures.
 *
 * @param workflowId persisted cold-wallet workflow identifier
 * @param psbt base64-encoded partially signed transaction awaiting offline signatures
 * @param psbtHash digest used to bind review and signing to the exact PSBT contents
 * @param feeSats network fee represented by the PSBT, in satoshis
 * @param amountSats intended payment amount, in satoshis
 * @param destinationAddress intended recipient address encoded in the transaction
 * @param inputs wallet outpoints selected as funding inputs
 */
public record KfeColdWalletPsbtResponse(
        /** Identifier used to attach signatures and advance this workflow. */
        UUID workflowId,
        /** Base64 PSBT payload for independent offline review and signing. */
        String psbt,
        /** Integrity digest of the returned PSBT payload. */
        String psbtHash,
        /** Total transaction fee in satoshis. */
        long feeSats,
        /** Intended amount paid to the destination in satoshis. */
        long amountSats,
        /** Recipient address encoded in the proposed payment. */
        String destinationAddress,
        /** UTXOs consumed by the proposed transaction. */
        List<KfeColdWalletPsbtRequest.Input> inputs) {
}
