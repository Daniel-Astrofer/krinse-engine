package com.kerosene.kfe.wallet.application.command;

import java.util.UUID;

/**
 * Owner-scoped request to update mutable wallet presentation metadata.
 *
 * @param ownerId authenticated owner used to enforce resource ownership
 * @param walletId wallet identity to update
 * @param label replacement label; validation and blank-value policy belong to the adapter/domain
 */
public record UpdateWalletCommand(
        long ownerId,
        UUID walletId,
        String label) {
}
