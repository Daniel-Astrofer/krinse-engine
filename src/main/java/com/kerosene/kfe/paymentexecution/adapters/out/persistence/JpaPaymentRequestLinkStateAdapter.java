package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestStatus;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestLinkStatePort;
import com.kerosene.kfe.paymentexecution.domain.model.*;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaPaymentRequestLinkStateAdapter implements PaymentRequestLinkStatePort {
    private final KfePaymentRequestRepository requests;
    private final KfeTransactionRepository transactions;
    private final EntityManager entityManager;

    public JpaPaymentRequestLinkStateAdapter(KfePaymentRequestRepository requests,
            KfeTransactionRepository transactions, EntityManager entityManager) {
        this.requests = requests;
        this.transactions = transactions;
        this.entityManager = entityManager;
    }

    @Override
    public PaymentRequestLinkSnapshot lockByPublicId(String publicId) {
        if (publicId == null || publicId.isBlank()) { throw notFound(); }
        var request = requests.findByPublicIdForUpdate(publicId).orElseThrow(JpaPaymentRequestLinkStateAdapter::notFound);
        entityManager.refresh(request, LockModeType.PESSIMISTIC_WRITE);
        if (!publicId.equals(request.getPublicId())) { throw notFound(); }
        return snapshot(request);
    }

    @Override
    public PaymentRequestLinkSnapshot lockById(long recipientUserId, UUID requestId) {
        if (recipientUserId <= 0L || requestId == null) { throw notFound(); }
        var request = requests.findByIdAndUserIdForUpdate(requestId, recipientUserId)
                .orElseThrow(JpaPaymentRequestLinkStateAdapter::notFound);
        entityManager.refresh(request, LockModeType.PESSIMISTIC_WRITE);
        requireIdentity(request, recipientUserId, requestId);
        return snapshot(request);
    }

    @Override
    public PaymentRequestLinkExecution lockExecution(long payerUserId, PaymentExecutionId executionId) {
        if (payerUserId <= 0L || executionId == null) { throw new IllegalArgumentException("KFE transaction not found."); }
        var tx = transactions.findByIdAndUserIdForUpdate(executionId.value(), payerUserId)
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
        // The query auto-flush preserves SETTLED and pricing written by the caller before refreshing.
        entityManager.refresh(tx, LockModeType.PESSIMISTIC_WRITE);
        if (!executionId.value().equals(tx.getId()) || !Long.valueOf(payerUserId).equals(tx.getUserId())) {
            throw new IllegalArgumentException("KFE transaction not found.");
        }
        return new PaymentRequestLinkExecution(executionId, payerUserId, ExecutionStatus.valueOf(tx.getStatus().name()),
                PaymentRail.valueOf(tx.getRail().name()), PaymentDirection.valueOf(tx.getDirection().name()),
                tx.getDestinationWalletId(), tx.getGrossAmountSats(), tx.getExternalReference());
    }

    @Override
    public void markPaid(PaymentRequestLinkSnapshot previous, PaymentExecutionId executionId) {
        if (executionId == null) { throw new IllegalArgumentException("execution id is required"); }
        var request = requests.findByIdAndUserId(previous.id(), previous.recipientUserId())
                .orElseThrow(JpaPaymentRequestLinkStateAdapter::notFound);
        requireIdentity(request, previous.recipientUserId(), previous.id());
        var current = snapshot(request);
        if (executionId.value().equals(current.paidExecutionId())) {
            return;
        }
        previous.requireOpenForLedger();
        if (!current.equals(previous)) {
            throw new IllegalStateException("Payment request changed after acceptance.");
        }
        request.markPaid(executionId.value());
        requests.save(request);
    }

    private static void requireIdentity(KfePaymentRequestEntity request, long owner, UUID id) {
        if (!id.equals(request.getId()) || !Long.valueOf(owner).equals(request.getUserId())) { throw notFound(); }
    }

    private static PaymentRequestLinkSnapshot snapshot(KfePaymentRequestEntity request) {
        return new PaymentRequestLinkSnapshot(request.getId(), request.getPublicId(), request.getUserId(),
                request.getWalletId(), PaymentRail.valueOf(request.getRail().name()),
                request.getStatus() == KfePaymentRequestStatus.OPEN, request.getAmountSats(),
                request.getExpiresAt() == null ? null : request.getExpiresAt().toInstant(ZoneOffset.UTC),
                request.getPaidTransactionId());
    }

    private static IllegalArgumentException notFound() { return new IllegalArgumentException("KFE payment request not found."); }
}
