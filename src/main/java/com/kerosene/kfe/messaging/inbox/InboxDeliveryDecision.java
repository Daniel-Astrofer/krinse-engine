package com.kerosene.kfe.messaging.inbox;

public enum InboxDeliveryDecision {
    PROCESS,
    DUPLICATE,
    DEFER,
    QUARANTINE
}
