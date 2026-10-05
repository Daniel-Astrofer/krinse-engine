package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.ValidatePaymentRequestCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentDestinationValidationPort;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

import java.util.Objects;

/** Scalar request validation; Bitcoin/Lightning integration checks stay behind the destination port. */
public final class ValidatePaymentRequestService {
    /** Maximum accepted principal or fee amount expressed as integer satoshis. */
    private static final long MAX_SATOSHIS = 2_100_000_000_000_000L;
    /** Validates rail-specific destination syntax through its integration adapter. */
    private final PaymentDestinationValidationPort destinations;

    /** Creates scalar validation with the destination-specific adapter. */
    /** @param destinations rail destination validation port */
    public ValidatePaymentRequestService(PaymentDestinationValidationPort destinations) {
        this.destinations = Objects.requireNonNull(destinations, "payment destination validation port is required");
    }

    /**
     * Validates idempotency key length, positive bounded amount, nonnegative bounded fee,
     * rail/direction compatibility, and required external outbound destinations.
     * @param command scalar payment request fields
     * @throws IllegalArgumentException when any request invariant is invalid
     */
    public void validate(ValidatePaymentRequestCommand command) {
        Objects.requireNonNull(command, "payment validation command is required");
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required.");
        }
        if (command.idempotencyKey().length() > IdempotencyKey.MAX_LENGTH) {
            throw new IllegalArgumentException("idempotencyKey must have at most 180 characters.");
        }
        if (command.amountSats() <= 0L) { throw new IllegalArgumentException("amountSats must be positive."); }
        if (command.amountSats() > MAX_SATOSHIS) {
            throw new IllegalArgumentException("amountSats exceeds maximum allowed limit (21M BTC).");
        }
        if (command.networkFeeSats() < 0L) { throw new IllegalArgumentException("networkFeeSats must be non-negative."); }
        if (command.networkFeeSats() > MAX_SATOSHIS) {
            throw new IllegalArgumentException("networkFeeSats exceeds maximum allowed limit (21M BTC).");
        }
        if (command.rail() == null || command.direction() == null) {
            throw new IllegalArgumentException("rail and direction are required");
        }
        if (command.rail() == PaymentRail.INTERNAL && command.direction() != PaymentDirection.INTERNAL) {
            throw new IllegalArgumentException("INTERNAL rail requires INTERNAL direction.");
        }
        if (command.direction() == PaymentDirection.INTERNAL && command.rail() != PaymentRail.INTERNAL) {
            throw new IllegalArgumentException("INTERNAL direction requires INTERNAL rail.");
        }
        if (command.rail() != PaymentRail.INTERNAL && command.direction() == PaymentDirection.OUTBOUND) {
            if (command.externalReference() == null || command.externalReference().isBlank()) {
                throw new IllegalArgumentException("externalReference is required for external outbound transactions.");
            }
            destinations.validate(command.rail(), command.externalReference());
        }
    }
}
