package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentInvoiceCommand;
import com.kerosene.kfe.paymentexecution.application.command.CancelPaymentRequestCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentCancellationHintsUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationFencePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationNotificationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationQueryPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionQueryRepository;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentInvoiceCancellationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationAuditPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationLockPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestCancellationStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.RelatedPaymentLookupPort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentCancellationHints;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentCancellationRejected;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentCancellationSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationSnapshot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * Atomic cancellation orchestration. The inbound adapter owns the transaction; all decisions
 * below use immutable state read after the request lock and execution/outbox fence.
 */
public final class CancelPaymentService {
    /** Resolves participant-visible transaction state before exposing cancellation actions. */
    private final PaymentCancellationQueryPort query;
    /** Computes whether the account may cancel and which aggregate is targeted. */
    private final PaymentCancellationHintsUseCase hints;
    /** Loads execution state after cancellation fences have been acquired. */
    private final PaymentCancellationStatePort state;
    /** Loads and transitions payment-request state. */
    private final PaymentRequestCancellationStatePort requests;
    /** Acquires the payment-request lock before outbox and execution fences. */
    private final PaymentRequestCancellationLockPort requestLock;
    /** Finds executions associated with a payment request. */
    private final RelatedPaymentLookupPort related;
    /** Fences outbox and execution updates against concurrent observers. */
    private final PaymentCancellationFencePort fence;
    /** Cancels provider-side invoices when the request snapshot requires it. */
    private final PaymentInvoiceCancellationPort invoices;
    /** Persists cancellation audit for a payment request. */
    private final PaymentRequestCancellationAuditPort requestAudit;
    /** Applies per-execution cancellation effects after whole-batch validation. */
    private final CancelPaymentEffectsService effects;
    /** Schedules user-facing refresh notifications after transaction commit. */
    private final PaymentCancellationNotificationPort notifications;
    /** Reloads a participant-visible response after the cancellation transition. */
    private final PaymentExecutionQueryRepository results;

    /**
     * Creates cancellation orchestration with state, lock, fence, provider, and side-effect ports.
     * @param query participant visibility lookup
     * @param hints cancellation eligibility and target resolver
     * @param state execution state loader
     * @param requests payment-request state loader and updater
     * @param requestLock request-level serialization lock
     * @param related related-execution lookup
     * @param fence execution and outbox fencing port
     * @param invoices provider invoice cancellation port
     * @param requestAudit payment-request cancellation audit port
     * @param effects execution cancellation effect service
     * @param notifications after-commit notification scheduler
     * @param results participant response query repository
     */
    public CancelPaymentService(
            PaymentCancellationQueryPort query, PaymentCancellationHintsUseCase hints,
            PaymentCancellationStatePort state, PaymentRequestCancellationStatePort requests,
            PaymentRequestCancellationLockPort requestLock, RelatedPaymentLookupPort related,
            PaymentCancellationFencePort fence, PaymentInvoiceCancellationPort invoices,
            PaymentRequestCancellationAuditPort requestAudit, CancelPaymentEffectsService effects,
            PaymentCancellationNotificationPort notifications, PaymentExecutionQueryRepository results) {
        this.query = query;
        this.hints = hints;
        this.state = state;
        this.requests = requests;
        this.requestLock = requestLock;
        this.related = related;
        this.fence = fence;
        this.invoices = invoices;
        this.requestAudit = requestAudit;
        this.effects = effects;
        this.notifications = notifications;
        this.results = results;
    }

    /**
     * Cancels the eligible transaction or its payment request under the required fences,
     * then schedules refresh and returns the updated participant-visible execution.
     * @param command authenticated cancellation request
     * @return refreshed transaction projection after cancellation
     * @throws PaymentCancellationRejected when ownership, eligibility, or state checks fail
     */
    public PaymentExecutionResult cancel(CancelPaymentCommand command) {
        long userId = command.userId();
        var id = command.paymentExecutionId();
        var visible = query.findParticipantVisible(userId, id)
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
        if (!id.equals(visible.executionId())) {
            throw new PaymentCancellationRejected();
        }
        if (visible.ownerUserId() != userId) {
            throw PaymentCancellationRejected.notEligible();
        }
        var eligibility = hints.hintsFor(userId, id);
        if (!eligibility.cancellable()) {
            throw PaymentCancellationRejected.notEligible();
        }
        if (PaymentCancellationHints.PAYMENT_REQUEST.equals(eligibility.cancelTarget())) {
            if (eligibility.paymentRequestId() == null) {
                throw new PaymentCancellationRejected();
            }
            cancelRequest(userId, eligibility.paymentRequestId(), id, false);
        } else if (PaymentCancellationHints.TRANSACTION.equals(eligibility.cancelTarget())) {
            fence.fence(List.of(id));
            var current = loadOwned(userId, id);
            if (!current.cancellable()) {
                throw new PaymentCancellationRejected();
            }
            effects.cancel(id, "Cancelado pelo usuário.");
        } else {
            throw new PaymentCancellationRejected();
        }
        notifications.publishAfterCommit(userId);
        return results.findParticipantVisibleById(userId, id)
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
    }

    /** Cancels a request aggregate and all cancellable related executions. */
    /** @param command authenticated payment-request cancellation @return cancelled request identifier */
    public UUID cancelPaymentRequest(CancelPaymentRequestCommand command) {
        return cancelRequest(command.userId(), command.paymentRequestId(), null, true);
    }

    /**
     * Locks and validates a request plus every related execution before provider calls,
     * audit writes, state transitions, or financial effects are applied.
     * @param userId owning account identifier
     * @param requestId payment request to cancel
     * @param explicit optional execution the caller targeted from a transaction view
     * @param notify whether to enqueue a post-commit account refresh
     * @return request identifier after cancellation or idempotent already-cancelled handling
     */
    private UUID cancelRequest(long userId, UUID requestId, PaymentExecutionId explicit, boolean notify) {
        // Observers follow the same order: request -> outbox -> execution -> financial effects.
        requestLock.lock(userId, requestId);
        var request = requests.load(userId, requestId);
        if (!requestId.equals(request.id()) || request.userId() != userId) {
            throw new PaymentCancellationRejected();
        }
        if (!request.cancellable()) {
            if (explicit != null) {
                throw new PaymentCancellationRejected();
            }
            return requestId;
        }

        var ids = new LinkedHashSet<>(related.findRelated(userId, requestId));
        if (explicit != null) {
            ids.add(explicit);
        }
        fence.fence(List.copyOf(ids));
        var snapshots = new ArrayList<PaymentCancellationSnapshot>(ids.size());
        for (var id : ids) {
            var current = loadOwned(userId, id);
            // Validate the entire batch before any RPC, audit or financial effect.
            if (current.status() == ExecutionStatus.SETTLED
                    || (current.incomplete() && !current.cancellable())
                    || (id.equals(explicit) && !current.cancellable())) {
                throw new PaymentCancellationRejected();
            }
            snapshots.add(current);
        }

        cancelInvoice(request);
        requests.markCancelled(request);
        requestAudit.recordCancelled(request);
        for (var current : snapshots) {
            if (current.incomplete()) {
                effects.cancel(current.executionId(), "Invoice/link de pagamento cancelado pelo usuário.");
            }
        }
        if (notify) {
            notifications.publishAfterCommit(userId);
        }
        return requestId;
    }

    /** Loads an execution and verifies both its identity and ownership before use. */
    /** @param userId expected owning account @param id execution identifier @return verified immutable state snapshot */
    private PaymentCancellationSnapshot loadOwned(long userId, PaymentExecutionId id) {
        var current = state.load(id);
        if (!id.equals(current.executionId()) || current.userId() != userId) {
            throw new PaymentCancellationRejected();
        }
        return current;
    }

    /** Cancels a provider invoice when required and fails the transaction if confirmation is absent. */
    /** @param request immutable payment-request state that determines provider cancellation inputs */
    private void cancelInvoice(PaymentRequestCancellationSnapshot request) {
        if (!request.invoiceCancellationRequired()) {
            return;
        }
        try {
            if (!invoices.cancel(new CancelPaymentInvoiceCommand(
                    request.userId(), request.paymentHash(), request.providerReference(), request.paymentRequest()))) {
                throw new IllegalStateException("O provedor não confirmou o cancelamento da invoice Lightning.");
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Não foi possível confirmar o cancelamento da invoice Lightning.", exception);
        }
    }
}
