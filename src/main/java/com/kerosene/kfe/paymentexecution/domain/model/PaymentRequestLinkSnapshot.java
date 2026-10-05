package com.kerosene.kfe.paymentexecution.domain.model;

import com.kerosene.kfe.paymentrequest.domain.PaymentRequestLifecyclePolicy;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Minimal recipient-owned request state used by an internal payment, not the full Request aggregate.
 * @param id persisted request identity
 * @param publicId client-visible request identifier
 * @param recipientUserId account that owns the requested funds
 * @param walletId recipient wallet identifier
 * @param rail request rail supported by the request
 * @param open whether the request can accept a payment
 * @param amountSats optional exact amount required by the request
 * @param expiresAt request expiry instant, if configured
 * @param paidExecutionId execution already linked as payment, if any
 */
public record PaymentRequestLinkSnapshot(UUID id, String publicId, long recipientUserId, UUID walletId,
        PaymentRail rail, boolean open, Long amountSats, Instant expiresAt, UUID paidExecutionId) {
    /** Requires a valid request identity, recipient, wallet, rail, and optional positive amount. */
    public PaymentRequestLinkSnapshot {
        Objects.requireNonNull(id, "request id is required");
        Objects.requireNonNull(walletId, "request wallet is required");
        Objects.requireNonNull(rail, "request rail is required");
        if (recipientUserId <= 0L || publicId == null || publicId.isBlank()
                || (amountSats != null && amountSats <= 0L)) {
            throw new IllegalArgumentException("payment request identity and amount must be valid");
        }
    }

    /** Confirms the request is open and uses a rail eligible for in-app ledger settlement. */
    /** @throws IllegalArgumentException when the request rail is not eligible @throws IllegalStateException when the request is closed or already paid */
    public void requireOpenForLedger() {
        if (rail != PaymentRail.INTERNAL && rail != PaymentRail.LIGHTNING) {
            throw new IllegalArgumentException("KFE payment request rail does not support INTERNAL ledger settlement. "
                    + "Use INTERNAL or LIGHTNING payment requests for in-app payments.");
        }
        if (!open || paidExecutionId != null) {
            throw new IllegalStateException("KFE payment request is no longer open.");
        }
    }

    /**
     * Validates open state, expiry, destination wallet, and optional exact amount at acceptance time.
     * @param destinationWalletId wallet selected by the paying user
     * @param paymentAmountSats proposed payment amount in integer satoshis
     * @param now current acceptance instant
     * @throws IllegalStateException when the request is expired or no longer open
     * @throws IllegalArgumentException when wallet or amount does not match the request
     */
    public void requireAccepts(UUID destinationWalletId, long paymentAmountSats, Instant now) {
        requireOpenForLedger();
        if (PaymentRequestLifecyclePolicy.isExpired(
                expiresAt == null ? null : java.time.LocalDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC),
                now == null ? null : java.time.LocalDateTime.ofInstant(now, java.time.ZoneOffset.UTC))) {
            throw new IllegalStateException("KFE payment request has expired.");
        }
        if (!walletId.equals(destinationWalletId)) {
            throw new IllegalArgumentException("KFE payment request destination wallet does not match.");
        }
        if (amountSats != null && amountSats.longValue() != paymentAmountSats) {
            throw new IllegalArgumentException("KFE payment request amount does not match.");
        }
    }

    /** Returns request state metadata while redacting the public identifier. */
    @Override
    public String toString() {
        return "PaymentRequestLinkSnapshot[id=" + id + ", recipientUserId=" + recipientUserId
                + ", rail=" + rail + ", open=" + open + ", reference=REDACTED]";
    }
}
