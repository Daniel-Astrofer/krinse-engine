package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeExecutionOutboxEntity;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationFencePort;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.policy.PaymentCancellationPolicy;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/** Locks commands before executions, matching worker preparation; no remote calls under this port. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaPaymentCancellationFenceAdapter implements PaymentCancellationFencePort {
    private final KfeExecutionOutboxRepository outboxes;
    private final KfeTransactionRepository transactions;
    private final EntityManager entityManager;
    private final PaymentCancellationPolicy policy = new PaymentCancellationPolicy();

    public JpaPaymentCancellationFenceAdapter(
            KfeExecutionOutboxRepository outboxes, KfeTransactionRepository transactions,
            EntityManager entityManager) {
        this.outboxes = outboxes;
        this.transactions = transactions;
        this.entityManager = entityManager;
    }

    @Override
    public void fence(List<PaymentExecutionId> executionIds) {
        var ids = executionIds.stream().map(PaymentExecutionId::value).distinct()
                .sorted(Comparator.comparing(Object::toString)).toList();
        if (ids.isEmpty()) {
            return;
        }
        var commands = outboxes.findByTransactionIdInForUpdate(ids);
        for (var command : commands) {
            entityManager.refresh(command, LockModeType.PESSIMISTIC_WRITE);
        }
        var closed = new java.util.HashSet<java.util.UUID>();
        for (var id : ids) {
            var tx = transactions.findByIdForUpdate(id)
                    .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
            // The caller may already hold a managed snapshot from its participant-visible query.
            entityManager.refresh(tx, LockModeType.PESSIMISTIC_WRITE);
            var status = ExecutionStatus.valueOf(tx.getStatus().name());
            if (policy.isClosed(status)) {
                closed.add(id);
                continue;
            }
            var related = commands.stream().filter(command -> id.equals(command.getTransactionId())).toList();
            boolean outbound = tx.getDirection() == KfeDirection.OUTBOUND && tx.getRail() != KfeRail.INTERNAL;
            boolean observed = hasText(tx.getBlockchainTxid()) || tx.getConfirmations() != 0
                    || hasText(tx.getPreparedRawTxHash())
                    || (outbound && (hasText(tx.getPaymentHash()) || hasText(tx.getProviderReference())));
            policy.requireUnstarted(status, observed, outbound, !related.isEmpty(),
                    related.stream().allMatch(JpaPaymentCancellationFenceAdapter::untouched));
        }
        // Only update after every execution passed: multi-payment cancellation is all-or-nothing.
        for (var command : commands) {
            if (!closed.contains(command.getTransactionId())) {
                command.setStatus("FAILED_FINAL");
                command.setLastError("USER_CANCELLED");
                command.setNextAttemptAt(null);
                outboxes.save(command);
            }
        }
    }

    private static boolean untouched(KfeExecutionOutboxEntity command) {
        return "PENDING".equals(command.getStatus()) && command.getAttempts() == 0
                && command.getClaimToken() == null && command.getClaimedAt() == null
                && !hasText(command.getClaimedBy()) && command.getLeaseExpiresAt() == null
                && command.getDispatchedAt() == null && !hasText(command.getProviderReference())
                && !hasText(command.getPreparedPayloadCiphertext()) && !hasText(command.getPreparedPayloadHash())
                && !hasText(command.getExecutionReference());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
