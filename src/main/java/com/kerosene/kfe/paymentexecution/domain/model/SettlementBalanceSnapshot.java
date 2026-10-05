package com.kerosene.kfe.paymentexecution.domain.model;

/**
 * Ledger-cached balance buckets, not proof of live spendable reserve assets.
 * @param role ledger account category
 * @param availableSats currently available customer/system balance
 * @param pendingSats unsettled incoming or outgoing balance
 * @param lockedSats balance reserved by an in-flight operation
 * @param autoHoldSats balance held automatically by ledger policy
 * @param observedSats total observed balance used as a solvency asset input
 */
public record SettlementBalanceSnapshot(SettlementWalletRole role, long availableSats, long pendingSats,
        long lockedSats, long autoHoldSats, long observedSats) {}
