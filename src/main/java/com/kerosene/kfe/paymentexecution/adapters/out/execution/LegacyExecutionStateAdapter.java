package com.kerosene.kfe.paymentexecution.adapters.out.execution;

import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionOutcomePort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionPreparationPort;
import com.kerosene.kfe.paymentexecution.application.result.ExecutionPreparation;
import com.kerosene.kfe.paymentexecution.domain.exception.ExecutionClaimLost;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import com.kerosene.kfe.paymentexecution.adapters.out.execution.KfeExecutionTransactionHelper;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Transitional bridge: the injected helper proxy remains the owner of each short transaction. */
@Component
public class LegacyExecutionStateAdapter implements ExecutionPreparationPort, ExecutionOutcomePort {
    /** Existing transaction helper whose Spring proxy owns each short database transaction. */
    private final KfeExecutionTransactionHelper helper;

    /** Creates the bridge and rejects missing transaction helper wiring. */
    public LegacyExecutionStateAdapter(KfeExecutionTransactionHelper helper) {
        this.helper = Objects.requireNonNull(helper, "execution helper is required");
    }

    /** Converts helper preparation state into the typed execution port and verifies claim-token continuity. */
    @Override
    public Optional<ExecutionPreparation> prepare(ExecutionClaim claim) {
        Objects.requireNonNull(claim, "execution claim is required");
        var result = helper.prepare(claim.outboxId(), claim.claimToken());
        if (result == null || !result.proceed()) {
            return Optional.empty();
        }
        if (!claim.claimToken().equals(result.claimToken())) {
            throw new ExecutionClaimLost(claim.outboxId());
        }
        return Optional.of(new ExecutionPreparation(
                result.operation(), result.transactionId(), result.userId(), result.sourceWalletLabel(),
                result.sourceWalletId(), result.externalReference(), result.amountSats(), result.networkFeeSats(),
                result.memo(), result.idempotencyKey(), result.quorumProposalHash(),
                result.feeRateSatsPerVbyte(), result.feeTargetBlocks()));
    }

    /** Persists an uncertain external result using the claim token as a fencing value. */
    @Override
    public void markUnknown(ExecutionClaim claim, UUID transactionId, String reference, String payload,
            String message) {
        Objects.requireNonNull(claim, "execution claim is required");
        helper.markUnknown(claim.outboxId(), transactionId, claim.claimToken(), reference, payload, message);
    }

    /** Persists a retryable failure while preserving current worker ownership checks. */
    @Override
    public void markRetryableFailure(ExecutionClaim claim, UUID transactionId, String code, String message) {
        Objects.requireNonNull(claim, "execution claim is required");
        helper.markRetryableFailure(claim.outboxId(), transactionId, claim.claimToken(), code, message);
    }

    /** Persists a terminal provider failure for the claimed execution. */
    @Override
    public void markFinalFailure(ExecutionClaim claim, UUID transactionId, String code, String message) {
        Objects.requireNonNull(claim, "execution claim is required");
        helper.markFinalFailure(claim.outboxId(), transactionId, claim.claimToken(), code, message);
    }
}
