package com.kerosene.kfe.wallet.domain.model;

/** Lifecycle state of a wallet as it is created, operated, or restricted. */
public enum WalletStatus {
    /** Initial state while wallet metadata or external custody provisioning is pending. */
    CREATING,
    /** Fully provisioned state that can serve operations allowed by wallet kind. */
    ACTIVE,
    /** Temporarily blocked state in which policy prevents ordinary spending or rotation. */
    FROZEN,
    /** Address rotation is in progress and may require recovery or reconciliation. */
    ROTATING_ADDRESS,
    /** Key generation/provisioning failed and requires a recovery path. */
    KEYGEN_FAILED,
    /** Required custody quorum is unavailable, so gated operations cannot proceed. */
    QUORUM_BLOCKED,
    /** Archived state retained for history and audit but unavailable for new operations. */
    ARCHIVED
}
