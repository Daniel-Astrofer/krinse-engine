package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.command.ScheduleExternalExecutionCommand;

import java.util.UUID;

/** Durable boundary used to schedule at-least-once external execution. */
public interface ExecutionCommandStore {
    /** Persists an at-least-once command in the owning business transaction. */
    /** @param command immutable external execution payload @return durable outbox command identifier */
    UUID enqueue(ScheduleExternalExecutionCommand command);
}
