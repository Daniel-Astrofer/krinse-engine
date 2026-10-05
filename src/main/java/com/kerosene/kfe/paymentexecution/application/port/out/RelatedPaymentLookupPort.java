package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import java.util.List;
import java.util.UUID;

/** Complete user-scoped discovery, in the financial transaction after locking the owned request. */
public interface RelatedPaymentLookupPort {
    /** Finds all executions related to the user's locked payment request. */
    List<PaymentExecutionId> findRelated(long userId, UUID paymentRequestId);
}
