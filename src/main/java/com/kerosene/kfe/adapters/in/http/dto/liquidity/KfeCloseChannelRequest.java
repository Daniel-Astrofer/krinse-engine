package com.kerosene.kfe.adapters.in.http.dto.liquidity;

import jakarta.validation.constraints.NotBlank;

/** Request to cooperatively or forcibly close a Lightning channel.
 *
 * @param channelPoint outpoint identifying the channel to close
 * @param force whether to request a force close instead of a cooperative close
 * @param peerOfflineBeyondThreshold whether the peer has been offline long enough to justify force closing
 * @param estimatedFeeRateSatVb optional fee rate estimate for the closing transaction, in satoshis per virtual byte
 */
public record KfeCloseChannelRequest(
        @NotBlank String channelPoint,
        Boolean force,
        Boolean peerOfflineBeyondThreshold,
        Long estimatedFeeRateSatVb) {
}
