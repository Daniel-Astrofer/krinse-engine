package com.kerosene.kfe.wallet.domain.model;

/** Defines wallet custody and spend behavior. */
public enum WalletKind {
    /** Internal application wallet managed by platform accounting policy. */
    INTERNAL,
    /** Custodial on-chain wallet whose keys are provisioned by a custody provider. */
    CUSTODIAL_ONCHAIN,
    /** Watch-only wallet that observes outputs but does not hold signing keys. */
    WATCH_ONLY
}
