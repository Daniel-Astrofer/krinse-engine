package com.kerosene.kfe.paymentexecution.application.port.out;

import java.util.UUID;

/** Dispatches a durable command after the business transaction has committed. */
public interface ExecutionCommandDispatcher {

    /** Attempts the claimed durable command inline after commit, without changing durable retry semantics. */
    DispatchResult dispatchImmediately(UUID outboxId, String workerId);

    /** Outcome of an optional immediate worker attempt after commit. */
    enum DispatchResult {
        /** The command was leased and processed by the immediate attempt. */
        PROCESSED,
        /** Another worker already holds the command lease or has claimed it. */
        ALREADY_CLAIMED
    }
}
