package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.port.in.GetPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionQueryRepository;
import com.kerosene.kfe.paymentexecution.application.query.GetPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.query.GetSubmittedPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;

import java.util.Optional;

/** Read-only participant-visible payment lookup. */
public final class GetPaymentService implements GetPaymentUseCase {

    /** Query port enforcing participant visibility for a payment execution. */
    private final PaymentExecutionQueryRepository repository;

    /** Creates payment lookup with the participant-visible execution query repository. */
    /** @param repository payment execution query boundary */
    public GetPaymentService(PaymentExecutionQueryRepository repository) {
        this.repository = repository;
    }

    /** Returns one participant-visible payment or reports it as not found. */
    /** @param query authenticated account and execution identifier @return visible payment projection @throws IllegalArgumentException when no participant-visible row exists */
    @Override
    public PaymentExecutionResult get(GetPaymentQuery query) {
        return repository.findParticipantVisibleById(query.userId(), query.paymentExecutionId())
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
    }

    /** Keeps submission-key recovery restricted to the authenticated owner. */
    @Override
    public Optional<PaymentExecutionResult> findSubmitted(GetSubmittedPaymentQuery query) {
        return repository.findOwnedBySubmissionKey(query.userId(), query.idempotencyKey());
    }
}
