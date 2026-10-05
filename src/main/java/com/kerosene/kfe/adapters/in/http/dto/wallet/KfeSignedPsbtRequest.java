package com.kerosene.kfe.adapters.in.http.dto.wallet;

import jakarta.validation.constraints.NotBlank;

/** Request carrying a wallet-signed PSBT back to the service for validation and finalization.
 *
 * @param signedPsbt Base64-encoded signed Partially Signed Bitcoin Transaction
 */
public record KfeSignedPsbtRequest(
        @NotBlank String signedPsbt) {
}
