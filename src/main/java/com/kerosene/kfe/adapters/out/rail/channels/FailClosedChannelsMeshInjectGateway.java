package com.kerosene.kfe.adapters.out.rail.channels;

/**
 * Honest stub: CHANNELS→LND inject is not wired.
 *
 * <p>Refuse channel opens that would treat empty/unrelated LND wallet funds as
 * mesh CHANNELS capital. See {@code VAULT_MESH_PLAN.md} Gaps and
 * {@code docs/backend/INFRASTRUCTURE.md}.
 */
public class FailClosedChannelsMeshInjectGateway implements ChannelsMeshInjectGateway {

    /** Stable refusal code distinguishing an intentionally unwired mesh funding path. */
    public static final String REASON = "CHANNELS_MESH_INJECT_NOT_WIRED";

    /** Refuses channel capital authorization because mesh injection is not implemented. */
    @Override
    public InjectResult authorizeOpen(long amountSats, String peerPubkey) {
        return InjectResult.refuse(REASON);
    }

    /** Refuses all soft reservations rather than reserving unrelated LND funds.
     * @param intentId requested stable reservation identifier
     * @param amountSats requested amount in satoshis
     * @param peerPubkey target Lightning peer
     * @return refusal result carrying {@link #REASON}
     */
    @Override
    public DebitResult reserveOpen(String intentId, long amountSats, String peerPubkey) {
        return DebitResult.refuse(REASON);
    }

    /** Refuses funding because no CHANNELS-key PSBT path is wired.
     * @param intentId reservation intent to fund
     * @param amountSats requested funding amount in satoshis
     * @param lndFundingAddress LND-generated destination address
     * @return refusal result carrying {@link #REASON}
     */
    @Override
    public FundResult fundOpen(String intentId, long amountSats, String lndFundingAddress) {
        return FundResult.refuse(REASON);
    }

    /** Refuses release because this adapter cannot create or own a reservation.
     * @param intentId reservation intent to release
     * @param amountSats reserved amount in satoshis
     * @param peerPubkey peer associated with the proposed channel
     * @return refusal result carrying {@link #REASON}
     */
    @Override
    public InjectResult releaseOpen(String intentId, long amountSats, String peerPubkey) {
        return InjectResult.refuse(REASON);
    }

    /** Refuses commit because no durable mesh reservation can be consumed by this adapter.
     * @param intentId reservation intent to commit
     * @return refusal result carrying {@link #REASON}
     */
    @Override
    public InjectResult commitOpen(String intentId) {
        return InjectResult.refuse(REASON);
    }
}
