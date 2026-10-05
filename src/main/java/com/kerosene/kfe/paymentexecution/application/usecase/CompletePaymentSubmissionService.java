package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.CompletePaymentSubmissionCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.IdempotencyReservationStore;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentSubmissionCompletionPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentSubmissionDashboardPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentWalletLookupPort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyReservation;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentSubmissionCompletionSnapshot;
import java.util.Objects;

/** Completion only: no authorization, money movement, state transition or provider dispatch. */
public final class CompletePaymentSubmissionService {
    /** Loads the completed payment snapshot and creates its participant response projection. */
    private final PaymentSubmissionCompletionPort state;
    /** Marks the matching idempotency reservation complete with the resulting payment state. */
    private final IdempotencyReservationStore idempotency;
    /** Resolves the destination owner for inbound-payment dashboard notifications. */
    private final PaymentWalletLookupPort wallets;
    /** Schedules dashboard refresh notifications only after successful transaction commit. */
    private final PaymentSubmissionDashboardPort dashboards;

    /** Creates submission completion with persisted-state, idempotency, wallet, and dashboard ports. */
    /** @param state completion snapshot and response projection port @param idempotency idempotency reservation store @param wallets destination wallet lookup port @param dashboards after-commit dashboard notifier */
    public CompletePaymentSubmissionService(PaymentSubmissionCompletionPort state, IdempotencyReservationStore idempotency,
            PaymentWalletLookupPort wallets, PaymentSubmissionDashboardPort dashboards) {
        this.state = state;
        this.idempotency = idempotency;
        this.wallets = wallets;
        this.dashboards = dashboards;
    }

    /**
     * Locks and verifies the persisted payment, completes the matching idempotency record,
     * schedules affected dashboards, and validates that the response matches persisted identity
     * and state. It does not authorize, move funds, transition execution, or dispatch a provider.
     * @param command completion inputs tied to the owning submission
     * @return verified payment execution response
     */
    public PaymentExecutionResult complete(CompletePaymentSubmissionCommand command) {
        Objects.requireNonNull(command, "completion command is required");
        var snapshot = Objects.requireNonNull(state.lockAndLoad(command.userId(), command.executionId()),
                "persisted payment is required");
        snapshot.requireReadyFor(command.userId(), command.executionId(), command.idempotencyKey());
        Long recipient = recipient(snapshot);
        var reservation = IdempotencyReservation.pending(command.idempotencyKey(), command.fingerprint());
        reservation.complete(command.executionId());
        if (idempotency.complete(command.userId(), reservation, snapshot.status())) {
            dashboards.publishAfterCommit(command.userId());
            if (recipient != null && recipient != command.userId()) { dashboards.publishAfterCommit(recipient); }
        }
        var response = state.saveAndProject(snapshot);
        if (response == null || !snapshot.executionId().value().equals(response.id())
                || snapshot.status() != response.status() || snapshot.rail() != response.rail()
                || snapshot.direction() != response.direction()) {
            throw new IllegalStateException("Submission response does not match the completed payment.");
        }
        return response;
    }

    /** Resolves a distinct recipient for inbound payments and validates destination ownership. */
    /** @param snapshot immutable persisted completion snapshot @return destination account for inbound flow, or null for outbound */
    private Long recipient(PaymentSubmissionCompletionSnapshot snapshot) {
        if (snapshot.direction() == PaymentDirection.OUTBOUND) { return null; }
        var destination = wallets.findById(snapshot.destinationWalletId())
                .orElseThrow(() -> new IllegalStateException("Submission destination wallet is missing."));
        if (!snapshot.destinationWalletId().equals(destination.id())
                || snapshot.direction() == PaymentDirection.INBOUND && snapshot.userId() != destination.userId()) {
            throw new IllegalStateException("Submission destination wallet does not match the payment.");
        }
        return destination.userId();
    }
}
