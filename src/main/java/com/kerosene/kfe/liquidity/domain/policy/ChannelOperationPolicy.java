package com.kerosene.kfe.liquidity.domain.policy;

import com.kerosene.kfe.liquidity.domain.model.ChannelOperationCommand;

import java.time.Instant;
import java.util.Objects;

/** Pure admission rules shared by enqueue and worker execution. */
public final class ChannelOperationPolicy {

    public Decision admit(ChannelOperationCommand command, Instant now, boolean duplicate) {
        Objects.requireNonNull(command, "command is required");
        Objects.requireNonNull(now, "now is required");
        if (duplicate) {
            return Decision.DUPLICATE;
        }
        if (!command.expiresAt().isAfter(now)) {
            return Decision.EXPIRED;
        }
        return Decision.ACCEPT;
    }

    public enum Decision {
        ACCEPT,
        DUPLICATE,
        EXPIRED
    }
}
