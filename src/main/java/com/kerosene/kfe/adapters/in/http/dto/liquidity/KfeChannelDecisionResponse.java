package com.kerosene.kfe.adapters.in.http.dto.liquidity;

import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelOperationType;

import java.time.LocalDateTime;
import java.util.UUID;

/** Audit projection of a liquidity-channel operation's policy decision and execution result.
 * @param id decision record identifier
 * @param operation channel operation evaluated by the policy engine
 * @param passed whether policy checks approved the requested operation
 * @param executed whether the approved operation was actually carried out
 * @param peerPubkey remote Lightning peer public key
 * @param channelPoint resulting or affected channel outpoint, when known
 * @param amountSats requested or executed channel amount in satoshis
 * @param decisionReason explanation for approval or rejection
 * @param providerReference external provider's operation reference
 * @param flagsJson serialized decision flags captured for audit
 * @param createdAt time when the decision was recorded
 */
public record KfeChannelDecisionResponse(
        UUID id,
        KfeChannelOperationType operation,
        boolean passed,
        boolean executed,
        String peerPubkey,
        String channelPoint,
        Long amountSats,
        String decisionReason,
        String providerReference,
        String flagsJson,
        LocalDateTime createdAt) {
}
