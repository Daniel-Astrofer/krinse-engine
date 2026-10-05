package com.kerosene.kfe.adapters.out.persistence.model.wallet;

/** Functional purpose assigned to a wallet address. */
public enum KfeWalletAddressRole {
    /** Address offered to receive external funds. */
    RECEIVE,
    /** Change output returning value to the same wallet after a spend. */
    CHANGE,
    /** Address tracked for chain activity without being newly issued to a user. */
    MONITOR
}
