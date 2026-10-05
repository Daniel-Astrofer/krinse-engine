package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

/**
 * Pricing inputs from a validated submission, before applying the authoritative network fee floor.
 * @param rail selected payment rail
 * @param direction transfer direction
 * @param amountSats principal amount in integer satoshis
 * @param requestedNetworkFeeSats client-requested fee reserve in integer satoshis
 * @param feeRateSatPerVbyte optional Bitcoin fee rate for network floor calculation
 * @param feeTargetBlocks optional confirmation target for network floor calculation
 */
public record PreparePaymentPricingCommand(
        PaymentRail rail,
        PaymentDirection direction,
        long amountSats,
        long requestedNetworkFeeSats,
        Long feeRateSatPerVbyte,
        Integer feeTargetBlocks) {
}
