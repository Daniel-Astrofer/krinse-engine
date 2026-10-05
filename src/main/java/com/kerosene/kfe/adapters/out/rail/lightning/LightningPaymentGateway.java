package com.kerosene.kfe.adapters.out.rail.lightning;

import com.kerosene.kfe.adapters.out.rail.custody.CustodyGateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Provider boundary for Lightning payments, with shared request preparation and idempotency rules.
 *
 * <p>Default methods validate required caller data and derive a stable provider-scoped execution
 * reference before delegating the operation that can move funds.
 */
public interface LightningPaymentGateway {

    /** @return true when this implementation can submit Lightning payments to a live provider */
    boolean isLive();

    /** @return stable provider identifier included in execution metadata and idempotency hashing */
    String providerName();

    /**
     * Submits a Lightning payment to the provider.
     *
     * @param command invoice, amount/fee limits, idempotency key, and authorization context
     * @return provider payment result and reconciliation identifiers
     */
    CustodyGateway.PaymentResult payLightning(CustodyGateway.LightningPaymentCommand command);

    /**
     * Validates and prepares provider-independent request state before any funds can move.
     * The execution reference is a SHA-256 digest scoped by provider name and idempotency key.
     *
     * @param command requested Lightning payment
     * @return normalized prepared payment data for execution and reconciliation
     * @throws IllegalArgumentException when command, payment request, or idempotency key is absent
     */
    default PreparedLightningPayment prepareLightning(CustodyGateway.LightningPaymentCommand command) {
        if (command == null || command.paymentRequest() == null || command.paymentRequest().isBlank()) {
            throw new IllegalArgumentException("Lightning payment command and destination are required.");
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException(
                    "Provider-backed Lightning payments require an idempotency key.");
        }
        String reference = sha256(providerName() + "|" + command.idempotencyKey());
        return new PreparedLightningPayment(
                "PROVIDER_IDEMPOTENT",
                command.userId(),
                command.walletId(),
                command.walletName(),
                command.paymentRequest(),
                null,
                null,
                null,
                reference,
                command.amountSats(),
                command.maxFeeSats(),
                command.description(),
                command.idempotencyKey(),
                command.authorizationProof());
    }

    /**
     * Converts prepared state back to the provider command and submits it through this gateway.
     *
     * @param prepared previously validated payment state
     * @return provider payment result
     * @throws IllegalArgumentException when prepared state is null
     */
    default CustodyGateway.PaymentResult payPreparedLightning(PreparedLightningPayment prepared) {
        if (prepared == null) {
            throw new IllegalArgumentException("Prepared Lightning payment is required.");
        }
        return payLightning(new CustodyGateway.LightningPaymentCommand(
                prepared.userId(),
                prepared.walletId(),
                prepared.walletName(),
                prepared.paymentRequest(),
                prepared.amountSats(),
                prepared.maxFeeSats(),
                prepared.description(),
                prepared.idempotencyKey(),
                prepared.authorizationProof()));
    }

    /**
     * Immutable validated input snapshot passed between preparation and provider execution.
     *
     * @param destinationKind normalized destination category (for this default path, provider idempotent)
     * @param userId owning user identifier
     * @param walletId source wallet identifier
     * @param walletName source wallet name used for provider context
     * @param paymentRequest Lightning invoice or payment request
     * @param nodePubkey optional node public key for keysend implementations
     * @param keysendPreimageBase64 optional keysend preimage encoded as Base64
     * @param paymentHash optional expected/payment hash for reconciliation
     * @param executionReference deterministic provider-scoped idempotency reference
     * @param amountSats requested payment amount in satoshis
     * @param maxFeeSats maximum routing fee accepted by the caller
     * @param description optional payment description
     * @param idempotencyKey caller-supplied stable retry key
     * @param authorizationProof proof required by the payment authorization policy
     */
    record PreparedLightningPayment(
            String destinationKind,
            Long userId,
            Long walletId,
            String walletName,
            String paymentRequest,
            String nodePubkey,
            String keysendPreimageBase64,
            String paymentHash,
            String executionReference,
            long amountSats,
            long maxFeeSats,
            String description,
            String idempotencyKey,
            String authorizationProof) {
    }

    /**
     * Produces lowercase hexadecimal SHA-256 over the supplied UTF-8 execution identity.
     *
     * @param value provider-scoped idempotency identity
     * @return 64-character lowercase hexadecimal digest
     * @throws IllegalStateException if the required SHA-256 implementation is unavailable
     */
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
