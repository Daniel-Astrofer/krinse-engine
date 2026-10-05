package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentSubmissionCommand;
import com.kerosene.kfe.paymentexecution.application.result.PreparedPaymentSubmission;

/** Prepares the current authorized intent through validation, settlement gates, and quorum. */
public interface PreparePaymentSubmissionUseCase {
    /** @param command locked intent identity and canonical request facts @return confirmed prepared transition and destination snapshot */
    PreparedPaymentSubmission prepare(PreparePaymentSubmissionCommand command);
}
