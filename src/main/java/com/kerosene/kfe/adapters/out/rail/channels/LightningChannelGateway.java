package com.kerosene.kfe.adapters.out.rail.channels;

import java.util.List;

/**
 * Structural Lightning channel operations (open / close / policy / inventory).
 * Separate from payment/invoice gateways (ISP).
 */
public interface LightningChannelGateway {

    /** @return {@code true} only when the configured Lightning provider is reachable and enabled */
    boolean isLive();

    /** @return stable provider name used in diagnostics and persisted execution metadata */
    String providerName();

    /** @return current open/active channel inventory; an empty list means no channels were returned */
    List<ChannelSnapshot> listChannels();

    /** Strict inventory for capital decisions; unsupported/failed observations must throw. */
    default ChannelInventory observeChannels() {
        throw new UnsupportedOperationException("Authoritative channel inventory unavailable");
    }

    record ChannelInventory(List<ChannelSnapshot> channels, List<PendingChannelSnapshot> pending) {
        public ChannelInventory { channels=List.copyOf(channels); pending=List.copyOf(pending); }
    }

    /**
     * Pending open / pending force-close / waiting-close channels (LND
     * {@code PendingChannels}). Default empty — adapters that omit pending
     * inventory must not claim double-open safety.
     */
    default List<PendingChannelSnapshot> listPendingChannels() {
        return List.of();
    }

    /**
     * Fresh on-chain address in the LND wallet, used to bind a CHANNELS mesh
     * withdraw target to a specific open decision. Default unsupported.
     */
    default String newOnchainAddress(String label) {
        throw new UnsupportedOperationException(
                "newOnchainAddress unsupported by " + providerName());
    }

    /**
     * Confirmed LND wallet (on-chain) balance in sats, or {@code -1} when unknown.
     */
    default long confirmedOnchainBalanceSats() {
        return -1L;
    }

    /**
     * Requests opening a channel using the provider and waits for its immediate response.
     * @param command peer, local capacity, and channel visibility/confirmation options
     * @return provider funding transaction, channel outpoint, and raw response snapshot
     */
    OpenChannelResult openChannel(OpenChannelCommand command);

    /**
     * Requests cooperative or force closure of an existing channel.
     * @param command channel outpoint and whether closure must be forced
     * @return closing transaction identifier and provider response snapshot
     */
    CloseChannelResult closeChannel(CloseChannelCommand command);

    /**
     * Updates fee and forwarding policy for an existing channel.
     * @param command channel outpoint and requested fee/timelock values
     * @return whether the provider accepted the update and its raw response snapshot
     */
    UpdatePolicyResult updateChannelPolicy(UpdatePolicyCommand command);

    /**
     * Optional circular rebalance via self-payment. Default: unsupported.
     */
    default CircularRebalanceResult attemptCircularRebalance(CircularRebalanceCommand command) {
        return CircularRebalanceResult.unsupported(providerName());
    }

    /** Creates an invoice and route constraints without sending a payment. */
    default PreparedCircularRebalance prepareCircularRebalance(CircularRebalanceCommand command) {
        throw new UnsupportedOperationException("Recoverable circular rebalance unavailable");
    }

    /** Sends only a previously persisted payment identity. */
    default CircularRebalanceResult submitCircularRebalance(PreparedCircularRebalance prepared) {
        return CircularRebalanceResult.unsupported(providerName());
    }

    /** Read-only recovery; absence or unavailable history must never authorize another send. */
    default CircularRebalanceResult lookupCircularRebalance(String paymentHash) {
        return CircularRebalanceResult.failed("UNKNOWN", null, "Payment reconciliation unavailable");
    }

    record PreparedCircularRebalance(String paymentHash, String paymentRequest,
                                    long maxFeeSats, java.util.List<String> outgoingChannelIds, String lastHopPubkey) {
        public PreparedCircularRebalance {
            if (paymentHash == null || !paymentHash.matches("[0-9a-fA-F]{64}")
                    || paymentRequest == null || paymentRequest.isBlank() || maxFeeSats < 0
                    || lastHopPubkey == null || !lastHopPubkey.matches("0[23][0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("Invalid prepared circular payment");
            }
            outgoingChannelIds = java.util.List.copyOf(outgoingChannelIds);
            if (outgoingChannelIds.isEmpty()) throw new IllegalArgumentException("Outgoing channel required");
            for (String id : outgoingChannelIds) Long.parseUnsignedLong(id);
        }
    }

    /** Inputs for attempting to rebalance liquidity through a Lightning self-payment.
     * @param targetChannelPoint outpoint whose local/remote balance should be improved
     * @param amountSats payment amount in satoshis
     * @param maxFeeSats hard maximum fee the caller permits for the route
     * @param memo invoice memo or provider-visible description, when supported
     */
    record CircularRebalanceCommand(
            /** Channel outpoint whose liquidity target is being adjusted. */ String targetChannelPoint,
            /** Amount to route through the rebalance, in satoshis. */ long amountSats,
            /** Maximum acceptable routing fee, in satoshis. */ long maxFeeSats,
            /** Optional invoice memo for the self-payment. */ String memo) {
    }

    /** Result of an optional circular rebalance attempt.
     * @param attempted whether a provider operation was actually submitted
     * @param succeeded whether the submitted self-payment completed successfully
     * @param supported whether the provider implements circular rebalancing at all
     * @param paymentHash Lightning payment hash, when an attempt created an invoice/payment
     * @param feeSats actual routing fee in satoshis, or zero when no successful fee is known
     * @param status stable provider-neutral outcome status
     * @param rawPayload provider response retained for diagnostics, when available
     * @param message concise outcome detail suitable for logs or operations
     */
    record CircularRebalanceResult(
            /** Whether the gateway attempted to submit a self-payment. */ boolean attempted,
            /** Whether the attempted rebalance reached successful settlement. */ boolean succeeded,
            /** Whether this provider supports the operation. */ boolean supported,
            /** Payment hash identifying the attempted Lightning payment, if created. */ String paymentHash,
            /** Actual fee paid, in satoshis. */ long feeSats,
            /** Provider-neutral outcome code such as {@code UNSUPPORTED} or {@code SUCCEEDED}. */ String status,
            /** Raw provider response for reconciliation or diagnostic review. */ String rawPayload,
            /** Human-readable result detail. */ String message) {

        /** Creates a result indicating the provider does not implement this operation.
         * @param provider provider name to include in the explanatory message
         * @return unsupported result without claiming that an attempt was made
         */
        public static CircularRebalanceResult unsupported(String provider) {
            return new CircularRebalanceResult(
                    false, false, false, null, 0L, "UNSUPPORTED", null,
                    "Circular rebalance not supported by " + provider);
        }

        /** Creates a result for a supported operation that was attempted and failed.
         * @param status provider-neutral failure status
         * @param raw raw provider payload for diagnostics
         * @param message concise failure explanation
         * @return attempted, supported, unsuccessful result
         */
        public static CircularRebalanceResult failed(String status, String raw, String message) {
            return new CircularRebalanceResult(true, false, true, null, 0L, status, raw, message);
        }

        /** Creates a successful result for a settled circular payment.
         * @param paymentHash settled payment identifier
         * @param feeSats actual fee charged, in satoshis
         * @param raw provider payload confirming success
         * @return attempted, supported, successful result with status {@code SUCCEEDED}
         */
        public static CircularRebalanceResult ok(String paymentHash, long feeSats, String raw) {
            return new CircularRebalanceResult(
                    true, true, true, paymentHash, feeSats, "SUCCEEDED", raw, "OK");
        }
    }

    /** Parameters passed to the provider when opening a Lightning channel.
     * @param peerPubkey remote Lightning node public key
     * @param localAmountSats local side of channel capacity requested, in satoshis
     * @param privateChannel whether the channel should be announced publicly
     * @param minConfsZero whether the provider may open using zero-confirmation funding
     */
    record OpenChannelCommand(
            /** Remote peer public key to connect to. */ String peerPubkey,
            /** Requested local channel capacity in satoshis. */ long localAmountSats,
            /** Whether the channel should remain private from graph announcements. */ boolean privateChannel,
            /** Whether zero-confirmation funding is permitted. */ boolean minConfsZero) {
    }

    /** Provider response for an accepted channel-open operation.
     * @param fundingTxid on-chain transaction that funds the channel
     * @param outputIndex transaction output index assigned to the channel funding output
     * @param channelPoint canonical outpoint identifying the funded channel
     * @param rawPayload unmodified provider response for diagnostics and reconciliation
     */
    record OpenChannelResult(
            /** Funding transaction identifier returned by the provider. */ String fundingTxid,
            /** Index of the channel funding output within the transaction. */ String outputIndex,
            /** Combined transaction/output reference used for subsequent channel operations. */ String channelPoint,
            /** Raw provider response captured for troubleshooting. */ String rawPayload) {
    }

    /** Parameters identifying a channel close request.
     * @param channelPoint canonical outpoint of the channel to close
     * @param force whether to bypass cooperative close and force-close the channel
     */
    record CloseChannelCommand(
            /** Channel funding outpoint targeted for closure. */ String channelPoint,
            /** {@code true} requests a force-close rather than cooperative negotiation. */ boolean force) {
    }

    /** Provider response for a channel close request.
     * @param closingTxid closing transaction identifier, if already known
     * @param rawPayload raw provider response for monitoring the close workflow
     */
    record CloseChannelResult(
            /** On-chain transaction identifier spending the channel funding output. */ String closingTxid,
            /** Raw provider response retained for close-state reconciliation. */ String rawPayload) {
    }

    /** Parameters for changing forwarding fees and timelock policy on a channel.
     * @param channelPoint canonical channel outpoint to update
     * @param baseFeeMsat fixed forwarding fee in millisatoshis
     * @param feeRatePpm proportional forwarding fee in parts per million
     * @param timeLockDelta required forwarding timelock delta in blocks
     */
    record UpdatePolicyCommand(
            /** Channel outpoint whose forwarding policy will change. */ String channelPoint,
            /** Fixed forwarding fee charged per HTLC, in millisatoshis. */ long baseFeeMsat,
            /** Proportional fee rate charged in parts per million. */ long feeRatePpm,
            /** Forwarding timelock delta requested from the provider. */ int timeLockDelta) {
    }

    /** Provider response for a forwarding-policy update.
     * @param ok whether the policy update was accepted
     * @param rawPayload raw response used to diagnose a rejected update
     */
    record UpdatePolicyResult(boolean ok, String rawPayload) {
    }

    /**
     * Snapshot of one channel before it reaches or after it leaves the active inventory.
     * @param remotePubkey remote node public key associated with the pending channel
     * @param channelPoint funding outpoint, when the provider has assigned one
     * @param status provider state such as {@code PENDING_OPEN}, {@code PENDING_FORCE_CLOSE}, or
     *               {@code WAITING_CLOSE}
     * @param capacitySats proposed or locked channel capacity in satoshis
     */
    record PendingChannelSnapshot(
            /** Remote node public key for the pending channel. */ String remotePubkey,
            /** Funding outpoint, when assigned by the provider. */ String channelPoint,
            /** Provider lifecycle such as {@code PENDING_OPEN} or {@code WAITING_CLOSE}. */ String status,
            /** Proposed or locked channel capacity, in satoshis. */ long capacitySats) {
    }

    /** Snapshot of one channel's current provider-reported balances and operational state.
     * @param channelPoint stable funding outpoint
     * @param remotePubkey connected remote node public key
     * @param active whether the channel is currently usable for routing
     * @param capacitySats total channel capacity in satoshis
     * @param localBalanceSats local side balance in satoshis
     * @param remoteBalanceSats remote side balance in satoshis
     * @param pendingHtlcs number of unresolved HTLCs on the channel
     * @param initiator whether this node opened the channel
     * @param commitFeeSats current commitment transaction fee in satoshis
     * @param chanId LND channel identifier encoded as an unsigned 64-bit decimal string, if known
     */
    record ChannelSnapshot(
            /** Funding outpoint used to identify the channel. */ String channelPoint,
            /** Remote Lightning node public key. */ String remotePubkey,
            /** Whether the provider currently considers the channel active for routing. */ boolean active,
            /** Total channel capacity in satoshis. */ long capacitySats,
            /** Local balance available on this node's side, in satoshis. */ long localBalanceSats,
            /** Remote peer's balance, in satoshis. */ long remoteBalanceSats,
            /** Number of unresolved HTLC contracts. */ int pendingHtlcs,
            /** Whether the local node initiated channel opening. */ boolean initiator,
            /** Commitment transaction fee currently reserved, in satoshis. */ long commitFeeSats,
            /** LND chan_id (uint64 as string), when known. */
            String chanId) {

        /**
         * Creates a snapshot for providers that do not expose an LND channel ID.
         * @param channelPoint funding outpoint
         * @param remotePubkey remote node public key
         * @param active whether the channel is active for routing
         * @param capacitySats total capacity in satoshis
         * @param localBalanceSats local balance in satoshis
         * @param remoteBalanceSats remote balance in satoshis
         * @param pendingHtlcs number of pending HTLCs
         * @param initiator whether the local node opened the channel
         * @param commitFeeSats commitment fee in satoshis
         */
        public ChannelSnapshot(
                String channelPoint,
                String remotePubkey,
                boolean active,
                long capacitySats,
                long localBalanceSats,
                long remoteBalanceSats,
                int pendingHtlcs,
                boolean initiator,
                long commitFeeSats) {
            this(
                    channelPoint,
                    remotePubkey,
                    active,
                    capacitySats,
                    localBalanceSats,
                    remoteBalanceSats,
                    pendingHtlcs,
                    initiator,
                    commitFeeSats,
                    null);
        }

        /**
         * Computes the local share of total channel capacity.
         * @return local balance divided by capacity, or {@code 0.0} when capacity is not positive
         */
        public double localRatio() {
            if (capacitySats <= 0L) {
                return 0.0d;
            }
            return (double) localBalanceSats / (double) capacitySats;
        }
    }
}
