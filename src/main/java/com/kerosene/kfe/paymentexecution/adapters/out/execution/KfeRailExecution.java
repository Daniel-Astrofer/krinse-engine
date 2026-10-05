package com.kerosene.kfe.paymentexecution.adapters.out.execution;

import java.util.UUID;

/** Legacy rail-execution contract retained for adapters during the worker-port migration. */
public interface KfeRailExecution {

    /** Returns whether this executor owns the specified durable outbox operation. */
    boolean supports(String operation);

    /** Performs the external operation using prepared data from the transaction helper. */
    void execute(UUID outboxId, KfeExecutionTransactionHelper.PreparationResult prep);
}
