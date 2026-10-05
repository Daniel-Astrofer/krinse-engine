package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentSubmissionStatePort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentSubmissionPricing;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentSubmissionSnapshot;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Updates only the already locked intent in the caller's submission transaction. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaPaymentSubmissionStateAdapter implements PaymentSubmissionStatePort {
    private final KfeTransactionRepository transactions;
    private final EntityManager entityManager;

    public JpaPaymentSubmissionStateAdapter(KfeTransactionRepository transactions, EntityManager entityManager) {
        this.transactions = transactions;
        this.entityManager = entityManager;
    }

    @Override
    public PaymentSubmissionSnapshot lockAndLoad(long userId, PaymentExecutionId executionId) {
        requireIdentityInputs(userId, executionId);
        var tx = transactions.findByIdAndUserIdForUpdate(executionId.value(), userId)
                .orElseThrow(JpaPaymentSubmissionStateAdapter::notFound);
        // The scoped query auto-flushes the caller's intent before refreshing potentially stale state.
        entityManager.refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        requireIdentity(tx, userId, executionId);
        return new PaymentSubmissionSnapshot(executionId, userId, ExecutionStatus.valueOf(tx.getStatus().name()),
                new IdempotencyKey(tx.getIdempotencyKey()), PaymentRail.valueOf(tx.getRail().name()),
                PaymentDirection.valueOf(tx.getDirection().name()), tx.getSourceWalletId(), tx.getDestinationWalletId(),
                tx.getGrossAmountSats(), tx.getExternalReference());
    }

    @Override
    public void applyPricing(PaymentSubmissionSnapshot expectedIntent, PaymentSubmissionPricing pricing) {
        Objects.requireNonNull(expectedIntent, "Expected payment intent is required.");
        Objects.requireNonNull(pricing, "Payment pricing is required.");
        var quote = Objects.requireNonNull(pricing.quote(), "Payment quote is required.");
        var display = Objects.requireNonNull(pricing.display(), "Payment display is required.");
        var tx = requireState(expectedIntent.userId(), expectedIntent.executionId(), KfeTransactionStatus.VALIDATING);
        if (!Objects.equals(tx.getIdempotencyKey(), expectedIntent.idempotencyKey().value())
                || tx.getRail() != KfeRail.valueOf(expectedIntent.rail().name())
                || tx.getDirection() != KfeDirection.valueOf(expectedIntent.direction().name())
                || !Objects.equals(tx.getSourceWalletId(), expectedIntent.sourceWalletId())
                || !Objects.equals(tx.getDestinationWalletId(), expectedIntent.destinationWalletId())
                || tx.getGrossAmountSats() != expectedIntent.amountSats()
                || !Objects.equals(tx.getExternalReference(), expectedIntent.externalReference())) {
            throw new IllegalStateException("Payment intent changed during submission preparation.");
        }
        tx.setGrossAmountSats(quote.grossAmountSats());
        tx.setReceiverAmountSats(quote.receiverAmountSats());
        tx.setNetworkFeeSats(quote.networkFeeSats());
        tx.setKeroseneFeeSats(quote.keroseneFeeSats());
        tx.setTotalDebitSats(quote.totalDebitSats());
        tx.setPricingPolicyVersion(quote.pricingPolicyVersion());
        tx.setDisplayBtcUsd(display.btcUsd());
        tx.setDisplayBtcEur(display.btcEur());
        tx.setDisplayBtcBrl(display.btcBrl());
        tx.setDisplayAmountUsd(display.amountUsd());
        tx.setDisplayAmountEur(display.amountEur());
        tx.setDisplayAmountBrl(display.amountBrl());
        transactions.save(tx);
    }

    @Override
    public void recordProposal(long userId, PaymentExecutionId executionId, String proposalHash) {
        if (proposalHash == null || proposalHash.isBlank()) {
            throw new IllegalArgumentException("Payment proposal hash is required.");
        }
        requireState(userId, executionId, KfeTransactionStatus.VALIDATING).setQuorumProposalHash(proposalHash);
    }

    @Override
    public void recordQuorum(long userId, PaymentExecutionId executionId, int ackCount) {
        requireState(userId, executionId, KfeTransactionStatus.QUORUM_SYNC).setQuorumAckCount(ackCount);
    }

    private KfeTransactionEntity requireState(long userId, PaymentExecutionId executionId, KfeTransactionStatus expected) {
        requireIdentityInputs(userId, executionId);
        var tx = transactions.findByIdAndUserId(executionId.value(), userId)
                .orElseThrow(JpaPaymentSubmissionStateAdapter::notFound);
        requireIdentity(tx, userId, executionId);
        if (tx.getStatus() != expected) {
            throw new IllegalStateException("Payment submission preparation state was not persisted.");
        }
        return tx;
    }

    private static void requireIdentityInputs(long userId, PaymentExecutionId executionId) {
        if (userId <= 0L || executionId == null) { throw notFound(); }
    }

    private static void requireIdentity(KfeTransactionEntity tx, long userId, PaymentExecutionId executionId) {
        if (!executionId.value().equals(tx.getId()) || !Long.valueOf(userId).equals(tx.getUserId())) { throw notFound(); }
    }

    private static IllegalArgumentException notFound() { return new IllegalArgumentException("KFE transaction not found."); }
}
