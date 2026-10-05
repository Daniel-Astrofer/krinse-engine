package com.kerosene.kfe.adapters.out.persistence.model.wallet;

/** Persisted stages of the PSBT preparation, signing, finalization, and broadcast process. */
public enum KfePsbtWorkflowStatus {
    /** PSBT proposal was created and is awaiting signatures. */
    CREATED,
    /** Required signing step completed and signed PSBT material was recorded. */
    SIGNED,
    /** Signed proposal was finalized into a raw transaction. */
    FINALIZED,
    /** Finalized transaction was submitted to the Bitcoin network. */
    BROADCAST,
    /** A workflow stage failed and requires review or recovery. */
    FAILED
}
