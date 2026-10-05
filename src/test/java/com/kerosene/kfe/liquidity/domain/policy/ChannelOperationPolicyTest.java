package com.kerosene.kfe.liquidity.domain.policy;

import com.kerosene.kfe.liquidity.domain.model.ChannelOperationCommand;
import com.kerosene.kfe.liquidity.domain.model.ChannelOperationIntent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelOperationPolicyTest {
    @Test
    void duplicateAndExpiredCommandsAreNotExecutedAgain() {
        Instant now = Instant.parse("2026-09-26T12:00:00Z");
        var command = new ChannelOperationCommand(
                UUID.randomUUID(), "open-1", ChannelOperationIntent.OPEN,
                "peer", null, 1_000, now.plusSeconds(60));
        var policy = new ChannelOperationPolicy();
        assertThat(policy.admit(command, now, false)).isEqualTo(ChannelOperationPolicy.Decision.ACCEPT);
        assertThat(policy.admit(command, now, true)).isEqualTo(ChannelOperationPolicy.Decision.DUPLICATE);
        assertThat(policy.admit(command, now.plusSeconds(61), false)).isEqualTo(ChannelOperationPolicy.Decision.EXPIRED);
    }
}
