package com.kerosene.kfe.paymentexecution.domain.model;

/** Ledger account classification used when aggregating solvency liabilities and assets. */
public enum SettlementWalletRole {
    /** Customer-owned balances counted as financial liabilities. */
    CUSTOMER,
    /** Platform profit balances tracked separately from customer liabilities. */
    SYSTEM_PROFIT,
    /** Wallet role excluded from customer and system-profit aggregates. */
    OTHER
}
