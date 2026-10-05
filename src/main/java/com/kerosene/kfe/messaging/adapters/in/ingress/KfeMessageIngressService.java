package com.kerosene.kfe.messaging.adapters.in.ingress;

import com.kerosene.kfe.messaging.adapters.out.persistence.KfeMessageInboxService;

import com.kerosene.kfe.messaging.application.WorkloadIdentity;
import com.kerosene.kfe.messaging.application.WorkloadOperationAuthorizer;
import com.kerosene.kfe.messaging.application.port.in.ReceiveMessageUseCase;
import com.kerosene.kfe.messaging.envelope.MessageEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Authenticated message ingress use case. Authorization is evaluated before the
 * message enters the durable inbox, so an unauthorized workload cannot create a
 * retryable or quarantined record.
 */
@Service
@ConditionalOnProperty(name = "kfe.messaging.ingress.enabled", havingValue = "true")
public final class KfeMessageIngressService implements ReceiveMessageUseCase {

    private final KfeMessageInboxService inbox;
    private final WorkloadOperationAuthorizer operationAuthorizer;

    public KfeMessageIngressService(
            KfeMessageInboxService inbox,
            WorkloadOperationAuthorizer operationAuthorizer) {
        this.inbox = inbox;
        this.operationAuthorizer = operationAuthorizer;
    }

    @Override
    public void receive(MessageEnvelope message, WorkloadIdentity workload) {
        if (message == null) {
            throw new IllegalArgumentException("message is required");
        }
        operationAuthorizer.requireAllowed(workload, message.type());
        inbox.accept(message);
    }
}
