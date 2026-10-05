package com.kerosene.kfe.messaging.application;

import com.kerosene.kfe.messaging.envelope.MessageEnvelope;

/** Inbound message port; implementations own idempotent aggregate effects. */
public interface MessageHandler {
    /**
     * Declares whether this handler understands the exact message type and schema revision.
     * Implementations should return false for unsupported revisions so the dispatcher can reject
     * or route the message explicitly rather than deserialize it with an incompatible contract.
     *
     * @param type canonical message type from the envelope
     * @param schemaVersion producer-declared schema revision
     * @return true when this handler can safely process that contract version
     */
    boolean supports(String type, int schemaVersion);

    /**
     * Applies the message's domain effects. Implementations own idempotency at their aggregate
     * boundary because delivery may be retried after a successful effect but before acknowledgement.
     *
     * @param message validated envelope accepted by {@link #supports(String, int)}
     */
    void handle(MessageEnvelope message);
}
