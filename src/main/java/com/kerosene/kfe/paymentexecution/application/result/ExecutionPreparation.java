package com.kerosene.kfe.paymentexecution.application.result;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable projection of the short, committed preparation step; it is not an authorization ticket.
 * Raw provider operations remain outside this projection.
 * @param operation durable execution operation discriminator
 * @param transactionId payment execution identity
 * @param userId account that owns the execution
 * @param sourceWalletLabel committed source wallet label, if present
 * @param sourceWalletId committed source wallet identity, if present
 * @param externalReference normalized destination/reference needed by the rail adapter
 * @param amountSats amount passed to the external operation in integer satoshis
 * @param networkFeeSats reserved network fee in integer satoshis
 * @param memo canonical memo passed to the rail adapter
 * @param idempotencyKey key used to make provider execution retries safe
 * @param quorumProposalHash proposal digest authorized by settlement quorum
 * @param feeRateSatsPerVbyte optional quoted Bitcoin fee rate
 * @param feeTargetBlocks optional requested Bitcoin confirmation target
 */
public record ExecutionPreparation(
        String operation, UUID transactionId, Long userId, String sourceWalletLabel,
        UUID sourceWalletId, String externalReference, long amountSats, long networkFeeSats,
        String memo, String idempotencyKey, String quorumProposalHash,
        Long feeRateSatsPerVbyte, Integer feeTargetBlocks) {
    /** Requires an operation discriminator and persisted transaction identity. */
    public ExecutionPreparation {
        Objects.requireNonNull(transactionId, "transactionId is required");
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("Execution operation is required.");
        }
    }

    /** Returns only transaction identity and redacts dispatch, reference, and authorization details. */
    @Override
    public String toString() {
        return "ExecutionPreparation[transactionId=" + transactionId + ", details=REDACTED]";
    }
}
