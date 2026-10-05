package com.kerosene.kfe.adapters.out.persistence.model.shared;

/** Payment or value-transfer rail recorded for a financial transaction. */
public enum KfeRail {
    /** Internal ledger transfer that does not use an external payment network. */
    INTERNAL,
    /** Bitcoin on-chain transfer settled through the blockchain network. */
    ONCHAIN,
    /** Lightning Network payment routed through an off-chain channel. */
    LIGHTNING
}
