package com.kerosene.kfe.adapters.out.persistence.model.wallet;

/** Custody and operational category of a persisted wallet. */
public enum KfeWalletKind {
    /** User wallet whose operations are mediated by the KFE internal ledger. */
    INTERNAL,
    /** Wallet whose on-chain key or signing operations are managed by a custody provider. */
    CUSTODIAL_ONCHAIN,
    /** Public-data-only wallet monitored for activity but unable to authorize spends. */
    WATCH_ONLY,
    /** Platform-owned wallet holding operational or settlement funds. */
    SYSTEM_FUNDS,
    /** Platform-owned wallet designated to receive retained profit or fee revenue. */
    SYSTEM_PROFIT
}
