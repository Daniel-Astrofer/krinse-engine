package com.kerosene.kfe.paymentexecution.adapters.in;

import org.springframework.stereotype.Component;
import com.kerosene.kfe.adapters.in.http.dto.paymentexecution.KfeSubmitTransactionRequest;
import com.kerosene.kfe.paymentexecution.application.command.ValidatePaymentRequestCommand;
import com.kerosene.kfe.paymentexecution.application.usecase.ValidatePaymentRequestService;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

/** Compatibility bridge for callers still using the legacy request DTO. */
@Component
public class KfeTransactionRequestValidator {

    /** Core structural validator that enforces payment request constraints. */
    private final ValidatePaymentRequestService validation;

    /** Supplies the core validator for legacy request conversion. */
    public KfeTransactionRequestValidator(ValidatePaymentRequestService validation) {
        this.validation = validation;
    }

    /** Converts legacy enum values and validates the request's non-authorization payment fields. */
    public void validate(KfeSubmitTransactionRequest request) {
        validation.validate(new ValidatePaymentRequestCommand(request.idempotencyKey(),
                request.rail() == null ? null : PaymentRail.valueOf(request.rail().name()),
                request.direction() == null ? null : PaymentDirection.valueOf(request.direction().name()),
                request.amountSats(), request.networkFeeSats(), request.externalReference()));
    }
}
