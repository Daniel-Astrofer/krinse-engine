package com.kerosene.kfe.adapters.in.http.dto.wallet;

import jakarta.validation.constraints.Size;

/** Request to update editable display metadata for a wallet.
 * A null label may be used by the service to represent no requested label change.
 *
 * @param label new display label, limited to 96 characters
 */
public record KfeUpdateWalletRequest(
        /** Optional replacement label shown to the wallet owner in client interfaces. */
        @Size(max = 96, message = "Wallet label must have at most 96 characters.")
        String label) {
}
