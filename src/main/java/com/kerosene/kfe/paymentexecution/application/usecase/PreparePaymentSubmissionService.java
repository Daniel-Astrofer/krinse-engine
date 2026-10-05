package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.*;
import com.kerosene.kfe.paymentexecution.application.port.in.*;
import com.kerosene.kfe.paymentexecution.application.port.out.*;
import com.kerosene.kfe.paymentexecution.application.result.PreparedPaymentSubmission;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import java.util.Map;

/** Prepare exactly once within the submit that authorized and created the intent. Never reserves or dispatches. */
public final class PreparePaymentSubmissionService {
    /** Locks and updates the payment intent within the caller-owned transaction. */
    private final PaymentSubmissionStatePort state;
    /** Resolves and validates source and destination wallet selection. */
    private final PaymentWalletsUseCase wallets;
    /** Calculates the rail-specific quote and complete debit amounts. */
    private final PreparePaymentPricingUseCase pricing;
    /** Produces the canonical proposal digest checked by the settlement quorum. */
    private final PaymentProposalHashPort hasher;
    /** Enforces idempotency, funds, quorum, solvency, and rail risk gates. */
    private final PaymentSettlementGateUseCase gate;
    /** Applies and confirms each legal execution lifecycle transition. */
    private final PaymentExecutionLifecycleUseCase lifecycle;
    /** Records payment fee-reserve adjustments for operational visibility. */
    private final PaymentSubmissionTelemetryPort telemetry;

    /**
     * Creates intent preparation with state, wallet, pricing, hashing, gate, lifecycle,
     * and telemetry collaborators.
     * @param state locked payment-intent persistence port
     * @param wallets wallet resolution use case
     * @param pricing pricing preparation use case
     * @param hasher canonical payment proposal hash port
     * @param gate settlement authorization gate
     * @param lifecycle execution lifecycle transition use case
     * @param telemetry fee adjustment telemetry port
     */
    public PreparePaymentSubmissionService(PaymentSubmissionStatePort state, PaymentWalletsUseCase wallets,
            PreparePaymentPricingUseCase pricing, PaymentProposalHashPort hasher, PaymentSettlementGateUseCase gate,
            PaymentExecutionLifecycleUseCase lifecycle, PaymentSubmissionTelemetryPort telemetry) {
        this.state = state; this.wallets = wallets; this.pricing = pricing; this.hasher = hasher;
        this.gate = gate; this.lifecycle = lifecycle; this.telemetry = telemetry;
    }

    /**
     * Validates and locks the intent, resolves wallets and fees, hashes and gates the proposal,
     * then records VALIDATING and QUORUM_SYNC transitions. It deliberately performs no reserve
     * or external dispatch; those are later steps in the same owning submission transaction.
     * @param command normalized values and authorization context for the locked intent
     * @return prepared submission snapshot and resolved destination
     */
    public PreparedPaymentSubmission prepare(PreparePaymentSubmissionCommand command) {
        var intent = state.lockAndLoad(command.userId(), command.executionId());
        intent.requireReadyFor(command.userId(), command.executionId(), command.externalReference(), command.paymentRequestPublicId());
        var id = intent.executionId();
        var validating = lifecycle.transition(id, ExecutionStatus.VALIDATING, "KFE_TRANSACTION_VALIDATING",
                Map.of("requestHash", command.requestFingerprint().value()));
        requireConfirmed(validating, id, ExecutionStatus.INTENT, ExecutionStatus.VALIDATING);
        var selection = wallets.resolve(new ResolvePaymentWalletsCommand(intent.userId(), intent.rail(), intent.direction(),
                intent.sourceWalletId(), intent.destinationWalletId(), command.externalReference()));
        var preparedPricing = pricing.prepare(new PreparePaymentPricingCommand(intent.rail(), intent.direction(),
                intent.amountSats(), command.requestedNetworkFeeSats(), command.feeRateSatPerVbyte(), command.feeTargetBlocks()));
        long reservedFee = preparedPricing.reservedNetworkFeeSats();
        long clientFee = Math.max(0L, command.requestedNetworkFeeSats());
        if (reservedFee > clientFee) {
            telemetry.feeReserveRaised(clientFee, reservedFee, command.feeRateSatPerVbyte(), command.feeTargetBlocks());
        }
        state.applyPricing(intent, preparedPricing);
        var quote = preparedPricing.quote();
        var proposalHash = hasher.hash(new PaymentProposal(id, intent.userId(), intent.rail(), intent.direction(),
                intent.sourceWalletId(), intent.destinationWalletId(), quote.grossAmountSats(), quote.receiverAmountSats(),
                quote.networkFeeSats(), quote.keroseneFeeSats(), quote.totalDebitSats(),
                command.externalReference(), command.paymentRequestPublicId()));
        if (proposalHash == null || proposalHash.isBlank()) { throw new IllegalStateException("Payment proposal hash was not produced."); }
        state.recordProposal(intent.userId(), id, proposalHash);
        var evidence = gate.requirePass(new PaymentSettlementGateCommand(intent.userId(), id,
                selection.source() != null ? selection.source().id() : intent.sourceWalletId(), intent.idempotencyKey(),
                true, intent.rail(), intent.direction(), intent.amountSats(), reservedFee,
                quote.totalDebitSats(), selection.requiresSourceReserve(), proposalHash));
        if (evidence == null) { throw new IllegalStateException("Payment settlement gate returned no evidence."); }
        var ready = lifecycle.transition(id, ExecutionStatus.QUORUM_SYNC, "KFE_TRANSACTION_QUORUM_SYNC",
                Map.of("proposalHash", proposalHash, "settlementGatePassed", 1, "quorumAckCount", evidence.quorumAckCount()));
        requireConfirmed(ready, id, ExecutionStatus.VALIDATING, ExecutionStatus.QUORUM_SYNC);
        state.recordQuorum(intent.userId(), id, evidence.quorumAckCount());
        return new PreparedPaymentSubmission(ready, selection.destination());
    }

    /** Requires the lifecycle adapter to confirm the exact execution and expected transition. */
    /** @param event transition event returned by the lifecycle port @param id expected execution @param previous required prior state @param target required resulting state @throws IllegalStateException when confirmation is missing or mismatched */
    private static void requireConfirmed(PaymentExecutionStatusChanged event, PaymentExecutionId id,
            ExecutionStatus previous, ExecutionStatus target) {
        if (event == null || !id.equals(event.executionId()) || event.previousStatus() != previous || event.currentStatus() != target) {
            throw new IllegalStateException("Payment submission transition was not confirmed.");
        }
    }
}
