package com.kerosene.kfe.adapters.in.http.dto.wallet;

import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletName;

/** UI option pairing the persisted wallet-name category with its human-readable display label.
 *
 * @param name stable wallet-name enum value used in API operations
 * @param label display label shown to the user
 */
public record KfeWalletNameOption(
        /** Stable enum value submitted when selecting this wallet category. */
        KfeWalletName name,
        /** Display label intended for client interfaces. */
        String label) {
}
