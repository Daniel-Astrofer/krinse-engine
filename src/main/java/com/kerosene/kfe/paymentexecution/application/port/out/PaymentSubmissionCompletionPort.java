package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentSubmissionCompletionSnapshot;

/** Owner-scoped persistence and consumer projection within the submission transaction. */
public interface PaymentSubmissionCompletionPort {
    /** Locks and loads a submission owned by the user before completion. */
    PaymentSubmissionCompletionSnapshot lockAndLoad(long userId, PaymentExecutionId executionId);
    /** Persists the expected state transition and updates its consumer projection atomically. */
    PaymentExecutionResult saveAndProject(PaymentSubmissionCompletionSnapshot expected);
}
