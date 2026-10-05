package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;

/** Authorizes the complete canonical request, not independently reusable factors. */
public interface PaymentApprovalPort {
    /** Approves all submitted factors as bound to this canonical request, without issuing reusable factor grants. */
    /** @param command canonical payment request and supplied factors */
    void approve(SubmitPaymentCommand command);
}
