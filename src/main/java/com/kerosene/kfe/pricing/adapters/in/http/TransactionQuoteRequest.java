package com.kerosene.kfe.pricing.adapters.in.http;

import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Validated HTTP request for estimating a transaction's fees and net amounts.
 *
 * @param rail payment rail used for fee and platform pricing
 * @param direction value movement direction from KFE's perspective
 * @param amountSats positive gross amount in satoshis
 * @param networkFeeSats nonnegative requested fee hint in satoshis
 */
public record TransactionQuoteRequest(
        @NotNull PaymentRail rail,
        @NotNull PaymentDirection direction,
        @Min(1) long amountSats,
        @Min(0) long networkFeeSats) {
}
