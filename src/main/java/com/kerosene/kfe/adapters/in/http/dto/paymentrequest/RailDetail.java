package com.kerosene.kfe.adapters.in.http.dto.paymentrequest;

import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Rail-specific payment instructions attached to a multi-rail payment request.
 * Only the values relevant to the selected rail are populated: on-chain uses an address,
 * while Lightning uses a BOLT11 invoice and its payment hash.
 *
 * @param rail rail that interprets the remaining payment fields
 * @param address on-chain destination address or legacy Lightning hash URI
 * @param paymentRequest Lightning BOLT11 invoice; null for non-Lightning rails
 * @param paymentHash Lightning payment hash; null for non-Lightning rails
 */
public record RailDetail(
        /** Rail to which the fields in this detail apply. */
        @JsonProperty("rail") KfeRail rail,
        /** On-chain address (tb1…) or ln:hash for Lightning. */
        @JsonProperty("address") String address,
        /** BOLT11 invoice, only when rail == LIGHTNING. */
        @JsonProperty("paymentRequest") String paymentRequest,
        /** Payment hash, only when rail == LIGHTNING. */
        @JsonProperty("paymentHash") String paymentHash) {
}
