package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.query.ListPaymentsQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;

import java.util.List;

/** Lists participant-visible payment history with bounded paging and an optional time filter. */
public interface ListPaymentsUseCase {
    /** @param query participant, page, page size, and date boundary @return visible payment projections */
    List<PaymentExecutionResult> list(ListPaymentsQuery query);
}
