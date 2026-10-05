package com.kerosene.kfe.paymentexecution.adapters.out.persistence;

import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeDirection;
import com.kerosene.kfe.adapters.out.persistence.model.paymentrequest.KfePaymentRequestEntity;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionEntity;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationQueryPort;
import com.kerosene.kfe.paymentexecution.application.port.out.RelatedPaymentLookupPort;
import com.kerosene.kfe.paymentexecution.application.result.CancellationEligibilitySnapshot;
import com.kerosene.kfe.paymentexecution.application.result.PaymentRequestCancellationReference;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentCancellationRejected;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentrequest.KfePaymentRequestRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** User-scoped projections independent of the response mapper and cancellation command graph. */
@Component
public class JpaPaymentCancellationQueryAdapter implements PaymentCancellationQueryPort, RelatedPaymentLookupPort {

    private static final String REQUEST_KEY_PREFIX = "payment-request:";

    private final KfeTransactionRepository transactions;
    private final KfePaymentRequestRepository requests;

    public JpaPaymentCancellationQueryAdapter(
            KfeTransactionRepository transactions, KfePaymentRequestRepository requests) {
        this.transactions = transactions;
        this.requests = requests;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CancellationEligibilitySnapshot> findParticipantVisible(
            long userId, PaymentExecutionId executionId) {
        return transactions.findParticipantVisibleById(
                        executionId.value(), userId, KfeRail.INTERNAL, KfeDirection.INTERNAL)
                .map(transaction -> new CancellationEligibilitySnapshot(
                        new PaymentExecutionId(transaction.getId()),
                        transaction.getUserId(),
                        transaction.getStatus() == null ? null : ExecutionStatus.valueOf(transaction.getStatus().name()),
                        transaction.getBlockchainTxid(),
                        // Receiver visibility must never expose the sender's request metadata.
                        Objects.equals(transaction.getUserId(), userId)
                                ? findLinkedRequest(transaction, userId).map(this::reference).orElse(null)
                                : null));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PaymentExecutionId> findRelated(long userId, UUID paymentRequestId) {
        // The caller owns the request lock; do not acquire transaction locks ahead of the outbox fence.
        var request = requests.findByIdAndUserId(paymentRequestId, userId)
                .filter(row -> Objects.equals(row.getUserId(), userId))
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request not found."));
        Set<PaymentExecutionId> result = new LinkedHashSet<>();
        if (request.getPaidTransactionId() != null) {
            var paid = transactions.findByIdAndUserId(request.getPaidTransactionId(), userId)
                    .orElseThrow(PaymentCancellationRejected::new);
            addOwned(result, paid, userId);
        }
        for (var transaction : transactions.findByUserIdAndIdempotencyKeyStartingWith(
                userId, REQUEST_KEY_PREFIX + request.getId() + ":")) {
            addOwned(result, transaction, userId);
        }
        if (request.getPublicId() != null && !request.getPublicId().isBlank()) {
            for (var transaction : transactions.findByUserIdAndExternalReference(userId, request.getPublicId())) {
                addOwned(result, transaction, userId);
            }
        }
        return List.copyOf(result);
    }

    private Optional<KfePaymentRequestEntity> findLinkedRequest(KfeTransactionEntity transaction, long userId) {
        var paid = owned(requests.findByPaidTransactionIdAndUserId(transaction.getId(), userId), userId);
        if (paid.isPresent()) {
            return paid;
        }
        String externalReference = transaction.getExternalReference();
        if (externalReference != null && !externalReference.isBlank()) {
            var byPublic = owned(requests.findByPublicIdAndUserId(externalReference.trim(), userId), userId);
            if (byPublic.isPresent()) {
                return byPublic;
            }
        }
        return requestId(transaction.getIdempotencyKey())
                .flatMap(id -> owned(requests.findByIdAndUserId(id, userId), userId));
    }

    private static Optional<KfePaymentRequestEntity> owned(Optional<KfePaymentRequestEntity> request, long userId) {
        return request.filter(row -> Objects.equals(row.getUserId(), userId));
    }

    private static Optional<UUID> requestId(String key) {
        if (key == null || !key.startsWith(REQUEST_KEY_PREFIX)) {
            return Optional.empty();
        }
        int end = key.indexOf(':', REQUEST_KEY_PREFIX.length());
        String id = key.substring(REQUEST_KEY_PREFIX.length(), end < 0 ? key.length() : end);
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private PaymentRequestCancellationReference reference(KfePaymentRequestEntity request) {
        return new PaymentRequestCancellationReference(
                request.getId(), request.getUserId(), request.getPublicId(),
                request.getStatus() == null ? null : PaymentRequestCancellationStatus.valueOf(request.getStatus().name()));
    }

    private static void addOwned(Set<PaymentExecutionId> result, KfeTransactionEntity transaction, long userId) {
        if (!Objects.equals(transaction.getUserId(), userId)) {
            throw new PaymentCancellationRejected();
        }
        result.add(new PaymentExecutionId(transaction.getId()));
    }
}
