package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.port.in.ListPaymentsUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionQueryRepository;
import com.kerosene.kfe.paymentexecution.application.query.ListPaymentsQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;

import java.util.List;

/** Read-only participant-visible payment listing with bounded pagination. */
public final class ListPaymentsService implements ListPaymentsUseCase {

    /** Query port that applies participant visibility, date filtering, and pagination. */
    private final PaymentExecutionQueryRepository repository;

    /** Creates the listing service with its execution query repository. */
    /** @param repository payment execution query boundary */
    public ListPaymentsService(PaymentExecutionQueryRepository repository) {
        this.repository = repository;
    }

    /** Clamps page and size to safe bounds before querying visible payment history. */
    /** @param query authenticated account, requested page, page size, and optional lower date bound @return participant-visible payment projections for the bounded page */
    @Override
    public List<PaymentExecutionResult> list(ListPaymentsQuery query) {
        int safePage = Math.max(0, query.page());
        int safeSize = Math.min(200, Math.max(1, query.size()));
        return repository.findParticipantVisible(query.userId(), safePage, safeSize, query.since());
    }
}
