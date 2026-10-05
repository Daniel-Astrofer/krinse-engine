package com.kerosene.kfe.adapters.out.persistence.model.wallet;

/** Availability and observation states retained for a wallet address record. */
public enum KfeWalletAddressStatus {
    /** Address may be issued or used according to its configured role. */
    ACTIVE,
    /** Address is retained for history but should no longer be issued as a new destination. */
    RETIRED,
    /** Address activity has been observed externally but is not currently an active issuance. */
    OBSERVED,
    /** Address is restricted and must not be used for normal wallet operations. */
    BLOCKED
}
