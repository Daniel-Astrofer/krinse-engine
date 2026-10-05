package com.kerosene.kfe.adapters.out.rail.channels;

/**
 * Mesh CHANNELS bucket → LND channel-funding inject.
 *
 * <p>Decision-gate ({@link #authorizeOpen}) must not mutate the ledger. Execution uses
 * reserve → fund-bind (LND address) → LND open → commit (or release on open failure).
 */
public interface ChannelsMeshInjectGateway {

    /**
     * Checks whether an open may proceed using mesh CHANNELS capital without moving funds.
     *
     * <p>This decision-gate operation must remain read-only: reservation, funding, and ledger
     * consumption belong to the later execution methods.</p>
     * @param amountSats proposed local channel amount in satoshis
     * @param peerPubkey Lightning peer public key for the proposed channel
     * @return allow/deny result and a stable reason code for policy or availability failure
     */
    InjectResult authorizeOpen(long amountSats, String peerPubkey);

    /**
     * Soft-reserve CHANNELS capital for a specific open decision. Destination is the
     * mesh allowlist tag {@code ln-channel-rebalance} (not the LN peer pubkey).
     *
     * <p>Must be idempotent for the same {@code intentId} while the reservation is live
     * (crash after reserve / before open retries the same decision).
     *
     * @param intentId stable id (typically {@code channels-inject-open-<decisionId>}) reused on
     *                 retry of the same open decision
     * @param amountSats channel capital to reserve, in satoshis
     * @param peerPubkey Lightning peer associated with the open request
     * @return authorized result with the durable intent ID, or refusal with a reason code
     */
    default DebitResult reserveOpen(String intentId, long amountSats, String peerPubkey) {
        InjectResult gate = authorizeOpen(amountSats, peerPubkey);
        if (!gate.authorized()) {
            return DebitResult.refuse(gate.reasonCode());
        }
        return DebitResult.refuse("CHANNELS_INJECT_RESERVE_NOT_WIRED");
    }

    /**
     * Builds, signs, and broadcasts a CHANNELS Taproot PSBT to the bound LND funding address.
     *
     * <p>The funding transaction includes amount and fees, must use the CHANNELS key rather than
     * the USERS omnibus key, and fails closed when construction, mesh signing, or broadcast fails.</p>
     * @param intentId durable mesh intent being funded
     * @param amountSats funding amount in satoshis
     * @param lndFundingAddress destination address returned by LND for channel opening
     * @return funding authorization, optional transaction ID, and reason code
     */
    default FundResult fundOpen(String intentId, long amountSats, String lndFundingAddress) {
        return FundResult.refuse("CHANNELS_INJECT_FUND_UNSUPPORTED");
    }

    /**
     * Releases a soft reservation after LND {@code openChannel} fails.
     * @param intentId durable intent holding the soft reservation
     * @param amountSats reserved amount in satoshis
     * @param peerPubkey peer originally associated with the open decision
     * @return release outcome and reason code
     */
    default InjectResult releaseOpen(String intentId, long amountSats, String peerPubkey) {
        return InjectResult.refuse("CHANNELS_INJECT_RELEASE_UNSUPPORTED");
    }

    /**
     * Durably consumes the reservation after LND reports a successful open.
     *
     * <p>Repeated commit calls must be idempotent so outbox retries after a crash cannot consume
     * the same intent twice.</p>
     * @param intentId durable reservation intent to commit
     * @return commit outcome and reason code
     */
    default InjectResult commitOpen(String intentId) {
        return InjectResult.refuse("CHANNELS_INJECT_COMMIT_UNSUPPORTED");
    }

    /** Result returned by the channel funding operation.
     * @param authorized whether the funding transaction was successfully prepared and broadcast
     * @param fundingTxid on-chain transaction identifier, absent when funding was refused
     * @param reasonCode stable machine-readable success or refusal reason
     */
    record FundResult(
            /** Whether funding completed successfully. */ boolean authorized,
            /** Transaction identifier broadcast to fund the channel, or {@code null} on refusal. */
            String fundingTxid,
            /** Stable machine-readable outcome code for callers and logs. */ String reasonCode) {
        /**
         * Creates a refused funding result with a non-null fallback reason.
         * @param reasonCode refusal reason; {@code null} selects the default funding refusal code
         * @return a result with authorization false and no transaction ID
         */
        public static FundResult refuse(String reasonCode) {
            return new FundResult(
                    false, null, reasonCode == null ? "CHANNELS_INJECT_FUND_REFUSED" : reasonCode);
        }

        /**
         * Creates a successful funding result.
         * @param fundingTxid broadcast transaction identifier
         * @param reasonCode success reason; {@code null} selects the default success code
         * @return an authorized result retaining the transaction identifier
         */
        public static FundResult ok(String fundingTxid, String reasonCode) {
            return new FundResult(
                    true,
                    fundingTxid,
                    reasonCode == null ? "CHANNELS_INJECT_FUND_OK" : reasonCode);
        }
    }

    /** Result of a policy-gate, reservation release, or reservation commit operation.
     * @param authorized whether the requested operation was accepted
     * @param reasonCode stable machine-readable allow/refusal reason
     */
    record InjectResult(
            /** Whether the operation was allowed or completed. */ boolean authorized,
            /** Stable machine-readable outcome reason returned to the caller. */ String reasonCode) {
        /**
         * Creates a refused result and substitutes the standard refusal code when needed.
         * @param reasonCode refusal reason, or {@code null} to use the default
         * @return a result with authorization false
         */
        public static InjectResult refuse(String reasonCode) {
            return new InjectResult(false, reasonCode == null ? "CHANNELS_INJECT_REFUSED" : reasonCode);
        }

        /**
         * Creates a successful result and substitutes the standard success code when needed.
         * @param reasonCode success reason, or {@code null} to use the default
         * @return a result with authorization true
         */
        public static InjectResult ok(String reasonCode) {
            return new InjectResult(true, reasonCode == null ? "CHANNELS_INJECT_OK" : reasonCode);
        }
    }

    /** Outcome of creating the durable soft reservation for an open operation.
     * @param authorized whether the reservation was accepted
     * @param intentId durable intent identifier, absent when reservation was refused
     * @param reasonCode stable machine-readable success or refusal reason
     */
    record DebitResult(
            /** Whether the reservation was accepted. */ boolean authorized,
            /** Durable intent ID required to fund, release, or commit the reservation. */ String intentId,
            /** Stable machine-readable outcome reason returned to the caller. */ String reasonCode) {
        /**
         * Creates a refused reservation result with a fallback refusal code.
         * @param reasonCode refusal reason, or {@code null} to use the standard debit refusal code
         * @return a denied result with no intent ID
         */
        public static DebitResult refuse(String reasonCode) {
            return new DebitResult(
                    false, null, reasonCode == null ? "CHANNELS_INJECT_DEBIT_REFUSED" : reasonCode);
        }

        /**
         * Creates an accepted reservation result.
         * @param intentId durable reservation intent identifier
         * @param reasonCode success reason, or {@code null} to use the default debit success code
         * @return an authorized result containing the durable intent ID
         */
        public static DebitResult ok(String intentId, String reasonCode) {
            return new DebitResult(
                    true,
                    intentId,
                    reasonCode == null ? "CHANNELS_INJECT_DEBIT_OK" : reasonCode);
        }
    }
}
