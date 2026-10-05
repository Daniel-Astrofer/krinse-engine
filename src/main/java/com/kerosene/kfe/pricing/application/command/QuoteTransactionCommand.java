package com.kerosene.kfe.pricing.application.command;

import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;

/**
 * Framework-independent input for calculating a transaction quote.
 *
 * @param rail transfer rail whose fee and pricing policies apply
 * @param direction whether the quoted flow is inbound or outbound
 * @param amountSats positive gross transfer amount in satoshis
 * @param requestedNetworkFeeSats caller-provided fee estimate or preference; must not be negative
 */
public record QuoteTransactionCommand(
        PaymentRail rail,
        PaymentDirection direction,
        long amountSats,
        long requestedNetworkFeeSats) {

    /** Validates required dimensions and positive amount before pricing begins. */
    public QuoteTransactionCommand {
        if (rail == null || direction == null) {
            throw new IllegalArgumentException("rail and direction are required");
        }
        if (amountSats <= 0L) {
            throw new IllegalArgumentException("amountSats must be positive");
        }
        if (requestedNetworkFeeSats < 0L) {
            throw new IllegalArgumentException("requestedNetworkFeeSats must be non-negative");
        }
    }
}
