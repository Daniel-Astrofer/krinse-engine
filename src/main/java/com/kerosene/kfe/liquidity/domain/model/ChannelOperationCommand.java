package com.kerosene.kfe.liquidity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Idempotent, expirable intent for a channel operation. */
public record ChannelOperationCommand(
        UUID commandId,
        String idempotencyKey,
        ChannelOperationIntent intent,
        String peerPubkey,
        String channelPoint,
        long amountSats,
        Instant expiresAt) {

    public ChannelOperationCommand {
        Objects.requireNonNull(commandId, "commandId is required");
        Objects.requireNonNull(intent, "intent is required");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (expiresAt == null) {
            throw new NullPointerException("expiresAt is required");
        }
        if (amountSats < 0) {
            throw new IllegalArgumentException("amountSats cannot be negative");
        }
        if ((intent == ChannelOperationIntent.OPEN || intent == ChannelOperationIntent.REBALANCE)
                && (peerPubkey == null || peerPubkey.isBlank())) {
            throw new IllegalArgumentException("peerPubkey is required for this operation");
        }
        if (intent == ChannelOperationIntent.CLOSE
                && (channelPoint == null || channelPoint.isBlank())) {
            throw new IllegalArgumentException("channelPoint is required for CLOSE");
        }
    }
}
