package com.kerosene.kfe.adapters.in.http.dto.ledger;

import java.time.Instant;
import java.util.UUID;

/** Wallet balance and lifecycle information displayed on the KFE dashboard.
 * @param walletId stable identifier of the wallet
 * @param kind backend wallet category used by routing and accounting
 * @param status current lifecycle state of the wallet
 * @param label user-facing label assigned to the wallet
 * @param walletName persisted wallet name used for lookup and display
 * @param walletTypeDescription human-readable description of the wallet type
 * @param asset asset ticker represented by the balance fields
 * @param spendable whether policy currently permits spending from this wallet
 * @param availableSats confirmed amount available for a new operation
 * @param pendingSats amount involved in operations that have not settled
 * @param lockedSats amount held by explicit wallet or transaction locks
 * @param autoHoldSats amount withheld automatically by wallet policy
 * @param observedSats amount observed on chain but not yet spendable
 * @param activeAddress currently assigned receiving address, if available
 * @param createdAt wallet creation timestamp
 * @param updatedAt timestamp of the latest wallet state update
 */
public record KfeDashboardWallet(
        UUID walletId,
        String kind,
        String status,
        String label,
        String walletName,
        String walletTypeDescription,
        String asset,
        boolean spendable,
        long availableSats,
        long pendingSats,
        long lockedSats,
        long autoHoldSats,
        long observedSats,
        String activeAddress,
        Instant createdAt,
        Instant updatedAt) {
}
