package com.kerosene.kfe.adapters.out.persistence.model.liquidity;

/** Persisted lifecycle states of an asynchronous Lightning channel rebalance job. */
public enum KfeChannelRebalanceJobStatus {
    /** Job is waiting to be claimed by a worker. */
    PENDING,
    /** A worker is executing the rebalance request. */
    IN_PROGRESS,
    /** Rebalance completed successfully. */
    COMPLETED,
    /** Rebalance reached a terminal error. */
    FAILED,
    /** Rebalance was cancelled before successful completion. */
    CANCELLED
}
