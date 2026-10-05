package com.kerosene.kfe.messaging.application.port.out;

import com.kerosene.kfe.messaging.application.WorkloadIdentity;
import com.kerosene.kfe.messaging.envelope.MessageEnvelope;

/** Outbound port for publishing a message over a channel authenticated as a specific workload. */
public interface AuthenticatedMessageTransport {
    /**
     * Publishes the envelope under the supplied workload identity. Implementations must bind the
     * identity to transport credentials and must never treat the message payload as its source.
     *
     * @param message immutable metadata and serialized payload to publish
     * @param workload local workload identity whose credentials authenticate the channel
     */
    void publish(MessageEnvelope message, WorkloadIdentity workload);
}
