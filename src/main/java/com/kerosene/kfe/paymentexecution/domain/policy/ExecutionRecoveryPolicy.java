package com.kerosene.kfe.paymentexecution.domain.policy;

import com.kerosene.kfe.paymentexecution.domain.model.ExternalExecutionEvidence;

import java.time.Duration;
import java.util.Objects;

/** Recovery decisions cannot treat an uncertain external outcome as proof that a refund is safe. */
public final class ExecutionRecoveryPolicy {

    public boolean requiresReconciliation(ExternalExecutionEvidence evidence) {
        Objects.requireNonNull(evidence, "External execution evidence is required.");
        return evidence.preparedCiphertextPresent()
                || evidence.preparedHashPresent()
                || evidence.executionReferencePresent()
                || evidence.outboxProviderReferencePresent()
                || evidence.transactionProviderReferencePresent()
                || evidence.blockchainTxidPresent()
                || evidence.paymentHashPresent();
    }

    public int nextAttempt(int attempts) {
        requireNonNegativeAttempts(attempts);
        return attempts == Integer.MAX_VALUE ? Integer.MAX_VALUE : attempts + 1;
    }

    public boolean retryExhausted(int attempts, int maxAttempts) {
        requireNonNegativeAttempts(attempts);
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("Maximum attempts must be positive.");
        }
        return (long) attempts + 1L >= maxAttempts;
    }

    public Duration retryDelay(int attemptsAfterIncrement) {
        requirePositiveAttempts(attemptsAfterIncrement);
        return Duration.ofMinutes(1L << Math.min(attemptsAfterIncrement, 5));
    }

    public Duration unknownDelay(int attemptsAfterIncrement) {
        requirePositiveAttempts(attemptsAfterIncrement);
        return Duration.ofSeconds(1L << Math.min(attemptsAfterIncrement, 8));
    }

    public Duration reconciliationDelay() {
        return Duration.ofMinutes(5);
    }

    private static void requireNonNegativeAttempts(int attempts) {
        if (attempts < 0) {
            throw new IllegalArgumentException("Attempts must not be negative.");
        }
    }

    private static void requirePositiveAttempts(int attemptsAfterIncrement) {
        if (attemptsAfterIncrement < 1) {
            throw new IllegalArgumentException("Attempts after increment must be positive.");
        }
    }
}
