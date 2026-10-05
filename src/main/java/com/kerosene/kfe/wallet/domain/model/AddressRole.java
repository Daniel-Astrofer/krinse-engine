package com.kerosene.kfe.wallet.domain.model;

/** Describes why a derived or monitored address belongs to a wallet. */
public enum AddressRole {
    /** Address presented to the wallet owner for incoming funds. */
    RECEIVE,
    /** Wallet-controlled destination for transaction change. */
    CHANGE,
    /** Address tracked for chain observation without being selected as a spend destination. */
    MONITOR
}
