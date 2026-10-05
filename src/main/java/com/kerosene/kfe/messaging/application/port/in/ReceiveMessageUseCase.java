package com.kerosene.kfe.messaging.application.port.in;

import com.kerosene.kfe.messaging.application.WorkloadIdentity;
import com.kerosene.kfe.messaging.envelope.MessageEnvelope;

/** Inbound application boundary for an authenticated, durable message submission. */
public interface ReceiveMessageUseCase {

    /**
     * Accepts a validated envelope together with the identity authenticated by the inbound adapter.
     * Implementations persist the durable inbox/idempotency boundary before acknowledging delivery.
     *
     * @param message message metadata and serialized payload to process
     * @param workload identity established by the authenticated transport
     */
    void receive(MessageEnvelope message, WorkloadIdentity workload);
}
