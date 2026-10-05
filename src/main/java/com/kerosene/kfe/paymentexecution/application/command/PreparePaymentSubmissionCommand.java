package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;
import java.util.Objects;

/**
 * Inputs retained only by the currently authorized submission while its intent is prepared;
 * this command is not a portable authorization ticket or a retry credential.
 * @param userId authenticated account identifier
 * @param executionId newly created payment intent being prepared
 * @param requestFingerprint fingerprint binding preparation to the canonical request
 * @param requestedNetworkFeeSats caller-requested fee reserve in satoshis
 * @param feeRateSatPerVbyte optional Bitcoin fee rate used for a minimum reserve
 * @param feeTargetBlocks optional desired Bitcoin confirmation target
 * @param externalReference canonical external destination reference
 * @param paymentRequestPublicId optional public payment request fulfilled by this intent
 */
public record PreparePaymentSubmissionCommand(long userId, PaymentExecutionId executionId,
        RequestFingerprint requestFingerprint, long requestedNetworkFeeSats, Long feeRateSatPerVbyte,
        Integer feeTargetBlocks, String externalReference, String paymentRequestPublicId) {
    /** Validates account identity, execution identity, and request binding before preparation. */
    public PreparePaymentSubmissionCommand {
        if (userId <= 0L) { throw new IllegalArgumentException("authenticated user id must be positive"); }
        Objects.requireNonNull(executionId, "execution id is required");
        Objects.requireNonNull(requestFingerprint, "request fingerprint is required");
    }
    /** Returns diagnostic identifiers while redacting external reference data. */
    @Override public String toString() {
        return "PreparePaymentSubmissionCommand[userId=" + userId + ", executionId=" + executionId + ", references=REDACTED]";
    }
}
