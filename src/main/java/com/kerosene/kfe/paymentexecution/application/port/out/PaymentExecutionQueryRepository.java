package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Participant-scoped read repository; adapters enforce visibility in the persistence query. */
public interface PaymentExecutionQueryRepository {

    /** Submission keys are visible only to the owner, never to other participants. */
    Optional<PaymentExecutionResult> findOwnedBySubmissionKey(long userId, IdempotencyKey key);

    /** Loads a single execution only when the user is an authorized participant. */
    /** @param userId requesting participant @param paymentExecutionId requested execution @return visible projection, or empty for missing/nonparticipant */
    Optional<PaymentExecutionResult> findParticipantVisibleById(
            long userId,
            PaymentExecutionId paymentExecutionId);

    /** Lists the participant's visible payment history using a bounded page and optional lower time bound. */
    /** @param userId requesting participant @param page zero-based page @param size maximum rows requested @param since optional creation-time lower bound @return participant-visible payment projections */
    List<PaymentExecutionResult> findParticipantVisible(
            long userId,
            int page,
            int size,
            Instant since);
}
