package com.kerosene.kfe.adapters.out.persistence.model.liquidity;

/** Lifecycle states persisted while a channel-capacity operation is queued and executed. */
public enum KfeChannelCapacityJobStatus {
    /** Job is stored and available for a worker to claim. */
    PENDING,
    /** A worker has claimed the job and is carrying out the provider operation. */
    IN_PROGRESS,
    /** Provider operation completed successfully. */
    COMPLETED,
    /** Provider operation reached a terminal failure. */
    FAILED,
    /** Request was cancelled before successful completion. */
    CANCELLED
}
