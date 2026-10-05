package com.kerosene.kfe.adapters.in.http.dto.liquidity;

import jakarta.validation.constraints.NotBlank;

/** Request to evaluate a liquidity rebalance for one Lightning channel.
 *
 * @param channelPoint outpoint identifying the channel to rebalance
 * @param estimatedCostSats estimated on-chain or routing cost in satoshis
 * @param expectedGainSats estimated benefit from the rebalance in satoshis
 */
public record KfeRebalanceChannelRequest(
        @NotBlank String channelPoint,
        Long estimatedCostSats,
        Long expectedGainSats) {
}
