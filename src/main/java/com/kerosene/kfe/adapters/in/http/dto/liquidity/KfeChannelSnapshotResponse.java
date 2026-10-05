package com.kerosene.kfe.adapters.in.http.dto.liquidity;

/** Point-in-time channel state returned by Lightning liquidity administration APIs.
 * @param channelPoint funding transaction outpoint identifying the channel
 * @param remotePubkey public key of the connected peer
 * @param active whether the channel is currently active for routing
 * @param capacitySats total channel capacity in satoshis
 * @param localBalanceSats local side balance in satoshis
 * @param remoteBalanceSats peer side balance in satoshis
 * @param pendingHtlcs number of unresolved HTLCs on the channel
 * @param initiator whether the local node opened the channel
 * @param commitFeeSats current commitment transaction fee in satoshis
 * @param localRatio local balance divided by total channel capacity
 */
public record KfeChannelSnapshotResponse(
        String channelPoint,
        String remotePubkey,
        boolean active,
        long capacitySats,
        long localBalanceSats,
        long remoteBalanceSats,
        int pendingHtlcs,
        boolean initiator,
        long commitFeeSats,
        double localRatio) {
}
