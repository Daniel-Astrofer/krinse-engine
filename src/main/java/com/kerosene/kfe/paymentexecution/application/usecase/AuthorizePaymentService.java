package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentApprovalPort;
import com.kerosene.kfe.paymentexecution.domain.exception.MissingLocalPaymentFactor;
import com.kerosene.kfe.paymentexecution.domain.policy.PaymentAuthorizationPolicy;

import java.util.Objects;

/** Chooses the existing approval policy without transport errors or credential conversion. */
public final class AuthorizePaymentService {
    /** Adapter-facing approval operation selected after applying the domain policy. */
    private final PaymentApprovalPort approval;
    /** Domain policy maps each rail/direction pair to required factors. */
    private final PaymentAuthorizationPolicy policy = new PaymentAuthorizationPolicy();

    /** Creates authorization coordination with the approval port. */
    /** @param approval payment approval and step-up factor adapter */
    public AuthorizePaymentService(PaymentApprovalPort approval) {
        this.approval = Objects.requireNonNull(approval, "payment approval port is required");
    }

    /** Applies the rail/direction policy and invokes approval only when factors are required. */
    /** @param command canonical payment request with submitted authorization factors @throws MissingLocalPaymentFactor when the required local PIN factor is absent */
    public void authorize(SubmitPaymentCommand command) {
        Objects.requireNonNull(command, "payment command is required");
        switch (policy.requirementFor(command.rail(), command.direction())) {
            case NONE -> { return; }
            case LOCAL_FACTOR_AND_ASSERTION -> {
                if (command.appPin() == null || command.appPin().isBlank()) { throw new MissingLocalPaymentFactor(); }
            }
            case WALLET_OUTBOUND_STEP_UP -> { }
        }
        approval.approve(command);
    }
}
