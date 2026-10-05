package com.kerosene.kfe.adapters.in.http.dto.liquidity;

/** Input used to calculate or apply a channel forwarding-fee adjustment.
 *
 * @param channelPoint outpoint identifying the channel whose policy is adjusted
 * @param currentPpm current forwarding fee in parts per million, when known
 * @param acceleratedDrain whether the policy should prioritize draining local liquidity
 * @param baseFeeMsat optional fixed forwarding fee in millisatoshis
 */
public record KfePpmAdjustRequest(
        String channelPoint,
        Long currentPpm,
        Boolean acceleratedDrain,
        Long baseFeeMsat) {
}
