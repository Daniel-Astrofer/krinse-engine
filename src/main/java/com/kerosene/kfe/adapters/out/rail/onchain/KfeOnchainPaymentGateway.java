package com.kerosene.kfe.adapters.out.rail.onchain;

import java.util.List;

/**
 * Contract for on-chain payment providers, including optional preflight and durable preparation hooks.
 * Implementations that cannot safely prepare before broadcast retain the explicit unsupported defaults.
 */
public interface KfeOnchainPaymentGateway {

    /** @return stable provider identifier for persisted execution records */
    String providerName();

    /**
     * Estimates whether the provider can fund the requested payment before execution.
     *
     * @param command destination, amount, fee ceiling, and request identity
     * @return provider-specific preflight result, or null when this provider has no preflight support
     */
    default OnchainFundingPreflight preflightOnchain(OnchainPreflightCommand command) {
        return null;
    }

    /**
     * Performs an on-chain payment according to the provider's supported execution contract.
     *
     * @param command destination, amount, fee policy, and authorization/idempotency data
     * @return provider reference, transaction ID, status, fee, and raw response
     */
    PaymentResult sendOnchain(OnchainPaymentCommand command);

    /**
     * Durably prepares a transaction before broadcast when the provider supports two-phase execution.
     *
     * @param command payment request to prepare
     * @return prepared transaction identity and signer/PSBT metadata
     * @throws UnsupportedOperationException when this provider has no durable prepare support
     */
    default PreparedOnchainPayment prepareOnchain(OnchainPaymentCommand command) {
        throw new UnsupportedOperationException(
                "This on-chain provider does not support durable prepare-before-broadcast.");
    }

    /**
     * Broadcasts a previously prepared transaction after external approval/persistence succeeds.
     *
     * @param prepared durable prepared transaction state
     * @return broadcast result
     * @throws UnsupportedOperationException when this provider has no prepared broadcast support
     */
    default PaymentResult broadcastPrepared(PreparedOnchainPayment prepared) {
        throw new UnsupportedOperationException(
                "This on-chain provider does not support durable prepared broadcasts.");
    }

    /**
     * Releases provider-side resources held during preparation when execution is abandoned.
     * Implementations without external prepare locks may leave the default no-op in place.
     *
     * @param prepared prepared payment whose external reservation should be released
     */
    default void releasePrepared(PreparedOnchainPayment prepared) {
        // Providers that lock external resources during preparation override this hook.
    }

    /**
     * Minimal request data used to estimate on-chain funding feasibility before signing.
     *
     * @param userId payment owner
     * @param walletId source wallet identifier
     * @param walletName source wallet name for provider context
     * @param destinationAddress recipient address
     * @param amountSats amount to send in satoshis
     * @param maxFeeSats maximum allowed miner fee
     * @param idempotencyKey stable caller retry key
     */
    record OnchainPreflightCommand(
            Long userId,
            Long walletId,
            String walletName,
            String destinationAddress,
            long amountSats,
            long maxFeeSats,
            String idempotencyKey) {
    }

    /**
     * Authorized on-chain payment request, including both explicit fee rate and confirmation target.
     *
     * @param userId payment owner
     * @param walletId source wallet identifier
     * @param walletName source wallet name for provider context
     * @param destinationAddress recipient Bitcoin address
     * @param amountSats amount to send in satoshis
     * @param maxFeeSats maximum permitted fee in satoshis
     * @param description payment memo or audit description
     * @param idempotencyKey stable caller retry key
     * @param authorizationProof proof accepted by the payment authorization boundary
     * @param feeRateSatsPerVbyte explicit fee tier in sat/vB, preferred when positive
     * @param confirmationTarget block target used when estimating fee without an explicit rate
     */
    record OnchainPaymentCommand(
            Long userId,
            Long walletId,
            String walletName,
            String destinationAddress,
            long amountSats,
            long maxFeeSats,
            String description,
            String idempotencyKey,
            String authorizationProof,
            /** Explicit sat/vB from the user fee tier; preferred over conf_target when &gt; 0. */
            Long feeRateSatsPerVbyte,
            /** Confirmation target blocks for Core estimatesmartfee path when no explicit rate. */
            Integer confirmationTarget) {

        /**
         * Compatibility constructor for callers that specify only a fee ceiling.
         *
         * @param userId payment owner
         * @param walletId source wallet identifier
         * @param walletName source wallet name
         * @param destinationAddress recipient address
         * @param amountSats amount in satoshis
         * @param maxFeeSats fee ceiling in satoshis
         * @param description audit description
         * @param idempotencyKey stable retry key
         * @param authorizationProof authorization evidence
         */
        public OnchainPaymentCommand(
                Long userId,
                Long walletId,
                String walletName,
                String destinationAddress,
                long amountSats,
                long maxFeeSats,
                String description,
                String idempotencyKey,
                String authorizationProof) {
            this(
                    userId,
                    walletId,
                    walletName,
                    destinationAddress,
                    amountSats,
                    maxFeeSats,
                    description,
                    idempotencyKey,
                    authorizationProof,
                    null,
                    null);
        }
    }

    /**
     * Result of estimating whether funding is available for a requested transaction.
     *
     * @param available whether the provider can proceed with funding
     * @param feeSats estimated network fee
     * @param psbtHash digest binding the estimated/funded PSBT when available
     * @param configuredSignerCount number of signers configured for approval
     * @param providerReference provider or preflight operation identifier
     */
    record OnchainFundingPreflight(
            boolean available,
            long feeSats,
            String psbtHash,
            int configuredSignerCount,
            String providerReference) {
    }

    /**
     * Durable prepared transaction snapshot to be stored before broadcast.
     *
     * @param rawTransaction finalized raw transaction hex
     * @param expectedTxid transaction identifier expected from broadcast
     * @param feeSats fee bound to the prepared transaction
     * @param fundedPsbtHash digest of the original funded PSBT
     * @param combinedPsbtHash digest after signer contributions were combined
     * @param rawTransactionHash digest of the finalized raw transaction
     * @param acceptedSigners signer identities whose contributions were accepted
     * @param intentId stable authorization/settlement intent identifier
     * @param metadataJson serialized provider metadata retained for audit/reconciliation
     */
    record PreparedOnchainPayment(
            String rawTransaction,
            String expectedTxid,
            long feeSats,
            String fundedPsbtHash,
            String combinedPsbtHash,
            String rawTransactionHash,
            List<String> acceptedSigners,
            String intentId,
            String metadataJson) {

        /**
         * Normalizes accepted signer collection to an immutable, nonnull list.
         */
        public PreparedOnchainPayment {
            acceptedSigners = acceptedSigners == null ? List.of() : List.copyOf(acceptedSigners);
        }
    }

    /**
     * Provider-facing result of an on-chain broadcast or payment operation.
     *
     * @param providerReference provider operation identifier
     * @param txid transaction identifier when known
     * @param paymentHash unused for ordinary on-chain payments; retained for shared payment contracts
     * @param status normalized provider lifecycle status
     * @param feeSats charged or estimated network fee in satoshis
     * @param rawPayload provider response or execution metadata
     */
    record PaymentResult(
            String providerReference,
            String txid,
            String paymentHash,
            String status,
            long feeSats,
            String rawPayload) {
    }

    /**
     * Signals that a request may have reached the provider but its final execution state is unknown.
     * Callers must reconcile by provider reference before retrying or releasing reserved funds.
     */
    class ProviderExecutionAmbiguous extends RuntimeException {

        /** Provider reference used to query the ambiguous operation. */
        private final String providerReference;
        /** Raw provider response retained for recovery diagnostics. */
        private final String rawPayload;

        /**
         * Creates an ambiguous-execution signal with provider evidence and original cause.
         *
         * @param message explanation of the uncertain result
         * @param providerReference provider operation identifier, if available
         * @param rawPayload provider response retained for reconciliation
         * @param cause underlying transport/provider exception
         */
        public ProviderExecutionAmbiguous(
                String message,
                String providerReference,
                String rawPayload,
                Throwable cause) {
            super(message, cause);
            this.providerReference = providerReference;
            this.rawPayload = rawPayload;
        }

        /** @return provider reference needed to reconcile the operation */
        public String providerReference() {
            return providerReference;
        }

        /** @return raw provider response captured when execution became ambiguous */
        public String rawPayload() {
            return rawPayload;
        }
    }
}
