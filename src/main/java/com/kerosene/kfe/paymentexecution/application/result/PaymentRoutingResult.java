package com.kerosene.kfe.paymentexecution.application.result;

import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import java.util.UUID;

/**
 * Routing outcome. Only outbound work is eligible for optional post-commit immediate dispatch.
 * @param transition confirmed lifecycle event produced by routing or internal settlement
 * @param rail rail selected for the payment
 * @param immediateDispatchOutboxId outbound command eligible for an immediate post-commit worker attempt, or null
 */
public record PaymentRoutingResult(PaymentExecutionStatusChanged transition, PaymentRail rail, UUID immediateDispatchOutboxId) {}
