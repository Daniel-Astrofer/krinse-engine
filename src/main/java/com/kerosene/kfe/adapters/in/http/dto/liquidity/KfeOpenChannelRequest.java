package com.kerosene.kfe.adapters.in.http.dto.liquidity;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request to open an outbound Lightning channel with the selected peer and funding options.
 *
 * @param peerPubkey compressed public key identifying the remote Lightning node
 * @param localAmountSats amount of local on-chain funding in satoshis; must be positive
 * @param estimatedFeeRateSatVb optional estimated funding transaction fee rate in satoshis per virtual byte
 * @param anchorsEnabled whether the channel should use anchor outputs when supported
 * @param privateChannel whether the channel should be announced to the public network
 * @param spendUnconfirmed whether unconfirmed wallet outputs may fund the channel
 */
public record KfeOpenChannelRequest(
        @NotBlank String peerPubkey,
        @NotNull @Min(1) Long localAmountSats,
        Long estimatedFeeRateSatVb,
        Boolean anchorsEnabled,
        Boolean privateChannel,
        Boolean spendUnconfirmed) {
}
