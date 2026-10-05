package com.kerosene.kfe.adapters.out.persistence.model.wallet;

/** Provisioning, operational, and administrative states of a wallet. */
public enum KfeWalletStatus {
    /** Wallet record exists but provisioning or key setup is still in progress. */
    CREATING,
    /** Wallet is provisioned and available subject to its spendability and policy settings. */
    ACTIVE,
    /** Wallet operations are frozen pending review or administrative action. */
    FROZEN,
    /** Address rotation is in progress while the wallet remains under controlled maintenance. */
    ROTATING_ADDRESS,
    /** Key generation failed and the wallet is not ready for normal operation. */
    KEYGEN_FAILED,
    /** Required signing quorum is unavailable or has blocked wallet operation. */
    QUORUM_BLOCKED,
    /** Wallet is archived and excluded from normal active operations. */
    ARCHIVED
}
