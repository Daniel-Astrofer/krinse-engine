package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.ResolvePaymentWalletsCommand;
import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.application.command.ValidatePaymentRequestCommand;
import com.kerosene.kfe.paymentexecution.application.port.in.AuthorizePaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.GetIdempotentPaymentUseCase;
import com.kerosene.kfe.paymentexecution.application.port.in.PaymentWalletsUseCase;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCanonicalDestinationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentRequestFingerprintPort;
import com.kerosene.kfe.paymentexecution.application.query.GetIdempotentPaymentQuery;
import com.kerosene.kfe.paymentexecution.application.result.PaymentPreflightResult;

import java.util.Objects;

/** Canonicalize, validate and replay before approval. The result is only for the immediate owning submit. */
public final class PreflightPaymentService {
    /** Resolves destination wallets and enforces wallet-level payment constraints. */
    private final PaymentWalletsUseCase wallets;
    /** Resolves a request's destination into canonical rail reference and memo values. */
    private final PaymentCanonicalDestinationPort canonicalDestinations;
    /** Applies structural, rail, and amount validation before idempotency replay. */
    private final ValidatePaymentRequestService validation;
    /** Creates a stable request fingerprint used to bind an idempotency key to request data. */
    private final PaymentRequestFingerprintPort fingerprints;
    /** Retrieves a matching prior result without creating a new execution. */
    private final GetIdempotentPaymentUseCase replays;
    /** Performs the required user and device authorization for a new payment. */
    private final AuthorizePaymentUseCase authorization;

    /** Creates the preflight pipeline and rejects any missing collaborator immediately. */
    /** @param wallets wallet resolution use case @param canonicalDestinations canonical destination resolver @param validation payment request validator @param fingerprints stable request fingerprint port @param replays idempotent result lookup @param authorization authorization use case */
    public PreflightPaymentService(PaymentWalletsUseCase wallets, PaymentCanonicalDestinationPort canonicalDestinations,
            ValidatePaymentRequestService validation, PaymentRequestFingerprintPort fingerprints,
            GetIdempotentPaymentUseCase replays, AuthorizePaymentUseCase authorization) {
        this.wallets = Objects.requireNonNull(wallets, "payment wallets port is required");
        this.canonicalDestinations = Objects.requireNonNull(canonicalDestinations, "canonical destination port is required");
        this.validation = Objects.requireNonNull(validation, "payment validation service is required");
        this.fingerprints = Objects.requireNonNull(fingerprints, "payment fingerprint port is required");
        this.replays = Objects.requireNonNull(replays, "idempotent replay port is required");
        this.authorization = Objects.requireNonNull(authorization, "payment authorization port is required");
    }

    /**
     * Resolves and canonicalizes the destination, validates the request, and checks for a
     * matching idempotent result before enforcing self-payment and authorization rules for a new payment.
     * A replay returns immediately and is intended only for the owning submit operation.
     * @param command raw transport-independent submission values
     * @return canonical command, request fingerprint, and optional prior result
     */
    public PaymentPreflightResult preflight(SubmitPaymentCommand command) {
        Objects.requireNonNull(command, "payment command is required");
        var destinationWallet = wallets.resolveDestinationReference(walletCommand(command));
        var resolved = Objects.equals(destinationWallet, command.destinationWalletId())
                ? command : command.withDestinationWalletId(destinationWallet);
        var destination = Objects.requireNonNull(canonicalDestinations.resolve(resolved), "canonical payment destination is required");
        var canonical = resolved.withCanonicalDestination(destination.externalReference(), destination.memo());
        validation.validate(new ValidatePaymentRequestCommand(canonical.idempotencyKey().value(), canonical.rail(),
                canonical.direction(), canonical.amountSats(), canonical.networkFeeSats(), canonical.externalReference()));
        var fingerprint = Objects.requireNonNull(fingerprints.fingerprint(canonical), "payment fingerprint is required");
        var existing = Objects.requireNonNull(replays.find(new GetIdempotentPaymentQuery(canonical.userId(),
                canonical.idempotencyKey(), fingerprint)), "idempotent replay result is required");
        if (existing.isPresent()) { return new PaymentPreflightResult(canonical, fingerprint, existing); }
        wallets.requireNotSelfPayment(walletCommand(canonical));
        authorization.authorize(canonical);
        return new PaymentPreflightResult(canonical, fingerprint, existing);
    }

    /** Projects submission values into the wallet-selection request contract. */
    /** @param command payment submission @return wallet resolution input */
    private static ResolvePaymentWalletsCommand walletCommand(SubmitPaymentCommand command) {
        return new ResolvePaymentWalletsCommand(command.userId(), command.rail(), command.direction(),
                command.sourceWalletId(), command.destinationWalletId(), command.externalReference());
    }
}
