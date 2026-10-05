package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.PreparePaymentRequestLinkCommand;
import com.kerosene.kfe.paymentexecution.application.command.CompletePaymentRequestLinkCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestLinkStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentWalletLookupPort;
import com.kerosene.kfe.paymentexecution.application.result.PreparedPaymentRequestLink;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import java.time.Clock;
import java.util.Optional;

/** Request acceptance and settlement linking inside the same authorized submit transaction. */
public final class PaymentRequestLinkService {
    /** Locks and transitions payment-request records as part of the payment transaction. */
    private final PaymentRequestLinkStatePort state;
    /** Validates recipient wallet ownership for the accepted request. */
    private final PaymentWalletLookupPort wallets;
    /** Supplies deterministic acceptance-time expiry checks. */
    private final Clock clock;

    /** Creates request linking with persistence, wallet lookup, and time-source dependencies. */
    /** @param state request and execution state lock/update port @param wallets recipient wallet lookup port @param clock authoritative request acceptance clock */
    public PaymentRequestLinkService(PaymentRequestLinkStatePort state, PaymentWalletLookupPort wallets, Clock clock) {
        this.state = state;
        this.wallets = wallets;
        this.clock = clock;
    }

    /**
     * Locks and validates an optional INTERNAL payment request, its expiry, amount, and
     * recipient wallet, returning a transaction-local acceptance snapshot when present.
     * @param command canonical request acceptance inputs
     * @return prepared link, or empty when the payment does not fulfill a public request
     */
    public Optional<PreparedPaymentRequestLink> prepare(PreparePaymentRequestLinkCommand command) {
        String publicId = command.publicId() == null ? null : command.publicId().trim();
        if (publicId == null || publicId.isBlank()) { return Optional.empty(); }
        if (command.rail() != PaymentRail.INTERNAL || command.direction() != PaymentDirection.INTERNAL) {
            throw new IllegalArgumentException("paymentRequestPublicId is only supported for INTERNAL payments.");
        }
        var request = state.lockByPublicId(publicId);
        if (!publicId.equals(request.publicId())) {
            throw new IllegalArgumentException("KFE payment request not found.");
        }
        request.requireAccepts(command.destinationWalletId(), command.amountSats(), clock.instant());
        var recipientWallet = wallets.findOwnedDestination(request.recipientUserId(), request.walletId())
                .orElseThrow(() -> new IllegalArgumentException("KFE payment request recipient wallet not found."));
        if (!request.walletId().equals(recipientWallet.id()) || request.recipientUserId() != recipientWallet.userId()) {
            throw new IllegalArgumentException("KFE payment request recipient wallet not found.");
        }
        // Spendability is checked in the following wallet-selection step, preserving the existing lock order.
        return Optional.of(new PreparedPaymentRequestLink(command.userId(), request, command.amountSats()));
    }

    /**
     * Locks the accepted request and settled execution, then links the request to that execution.
     * Expiry is intentionally not rechecked after acceptance while the request lock is retained.
     * @param command accepted request and settled execution identifiers
     * @throws IllegalStateException when another execution already paid the request or state changed
     */
    public void complete(CompletePaymentRequestLinkCommand command) {
        var accepted = command.link();
        var current = state.lockById(accepted.request().recipientUserId(), accepted.request().id());
        if (current.paidExecutionId() != null) {
            if (current.paidExecutionId().equals(command.executionId().value())) {
                return;
            }
            throw new IllegalStateException("KFE payment request was already settled by another execution.");
        }
        current.requireOpenForLedger();
        if (!current.equals(accepted.request())) {
            throw new IllegalStateException("Payment request changed after acceptance.");
        }
        // Expiry is checked on acceptance, not again after settlement while this request lock is retained.
        var execution = state.lockExecution(command.userId(), command.executionId());
        execution.requireSettledFor(command.userId(), command.executionId(), current, accepted.amountSats());
        state.markPaid(current, command.executionId());
    }
}
