package com.kerosene.kfe.adapters.out.rail.custody;

import com.kerosene.kfe.adapters.out.rail.lightning.LightningInvoiceGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningPaymentGateway;

import java.time.LocalDateTime;

/**
 * Outbound adapter contract for custodial on-chain and Lightning wallet operations.
 *
 * <p>Commands carry the owning user and wallet context so implementations can enforce wallet
 * ownership and authorization before invoking a provider. Results retain provider references and
 * raw payloads for reconciliation; callers should avoid exposing those raw payloads to users.</p>
 */
public interface CustodyGateway extends LightningInvoiceGateway, LightningPaymentGateway {

    /** Reports whether the configured custody provider is available for live operations.
     * @return true when provider operations can be attempted
     */
    boolean isLive();

    /** Returns the stable provider identifier used in transaction records and diagnostics.
     * @return provider name
     */
    String providerName();

    /** Creates a receiving address in the requested custodial wallet.
     * @param command owner, wallet and label context for address allocation
     * @return generated address and provider references
     */
    GeneratedOnchainAddress createOnchainAddress(OnchainAddressCommand command);

    /** Creates an inbound Lightning invoice with the requested amount and expiry.
     * @param command invoice owner, wallet, amount, memo and expiry settings
     * @return invoice payment request, hash, address and provider reference
     */
    GeneratedLightningInvoice createLightningInvoice(LightningInvoiceCommand command);

    /** Looks up the settlement state of an inbound Lightning invoice.
     * @param command invoice identity and wallet context used for lookup
     * @return normalized invoice state and provider observation metadata
     */
    IncomingLightningInvoiceStatus getLightningInvoiceStatus(LightningInvoiceStatusCommand command);

    /** Attempts to cancel an unpaid incoming invoice.
     * @param command invoice identity and wallet context used for cancellation
     * @return true when the provider confirms cancellation
     */
    boolean cancelLightningInvoice(LightningInvoiceCancellationCommand command);

    /** Sends an on-chain payment from a custodial wallet.
     * @param command destination, amount, idempotency and authorization context
     * @return provider execution reference, transaction ID and fee details
     */
    PaymentResult sendOnchain(OnchainPaymentCommand command);

    /** Pays a Lightning invoice from a custodial wallet.
     * @param command invoice, amount/fee bounds, idempotency and authorization context
     * @return provider execution reference, payment hash and fee details
     */
    PaymentResult payLightning(LightningPaymentCommand command);

    /** Input needed to allocate a custodial on-chain receiving address.
     * @param userId owner whose wallet receives funds
     * @param walletId internal wallet identifier, when available
     * @param walletName provider wallet name
     * @param label address label used for provider-side identification
     */
    record OnchainAddressCommand(
            Long userId,
            Long walletId,
            String walletName,
            String label) {
    }

    /** Address allocation result returned by a custody provider.
     * @param address generated on-chain address
     * @param walletReference provider-side wallet identifier
     * @param providerReference provider-side address or allocation identifier
     */
    record GeneratedOnchainAddress(
            String address,
            String walletReference,
            String providerReference) {
    }

    /** Input for creating an inbound Lightning invoice.
     * @param userId invoice owner
     * @param walletId internal wallet identifier, when available
     * @param walletName provider wallet name
     * @param amountSats invoice amount in satoshis
     * @param memo invoice description
     * @param expiresInSeconds invoice lifetime in seconds
     */
    record LightningInvoiceCommand(
            Long userId,
            Long walletId,
            String walletName,
            long amountSats,
            String memo,
            int expiresInSeconds) {
    }

    /** Newly created Lightning invoice and its provider identifiers.
     * @param paymentRequest encoded BOLT11 invoice
     * @param paymentHash invoice payment hash
     * @param lightningAddress associated Lightning address, when supplied
     * @param providerReference provider-side invoice identifier
     * @param expiresAt invoice expiry timestamp
     */
    record GeneratedLightningInvoice(
            String paymentRequest,
            String paymentHash,
            String lightningAddress,
            String providerReference,
            LocalDateTime expiresAt) {
    }

    /** Lookup key and owner context for an inbound Lightning invoice.
     * @param userId invoice owner
     * @param walletId internal wallet identifier, when available
     * @param walletName provider wallet name
     * @param paymentHash invoice payment hash
     * @param providerReference provider-side invoice identifier
     * @param paymentRequest encoded invoice, used as a fallback lookup key
     */
    record LightningInvoiceStatusCommand(
            Long userId,
            Long walletId,
            String walletName,
            String paymentHash,
            String providerReference,
            String paymentRequest) {
    }

    /** Owner context and identifying data needed to cancel an unpaid Lightning invoice.
     * @param userId invoice owner
     * @param walletId internal wallet identifier, when available
     * @param walletName provider wallet name
     * @param paymentHash invoice payment hash
     * @param providerReference provider-side invoice identifier
     * @param paymentRequest encoded invoice, used as a fallback cancellation key
     */
    record LightningInvoiceCancellationCommand(
            Long userId,
            Long walletId,
            String walletName,
            String paymentHash,
            String providerReference,
            String paymentRequest) {
    }

    /** Normalized inbound Lightning invoice observation from the custody provider.
     * @param status provider-independent state label
     * @param receivedSats amount received in satoshis, when settled
     * @param settledAt settlement timestamp, when known
     * @param rawPayload provider response retained for reconciliation
     * @param paymentHash invoice payment hash
     * @param addIndex provider invoice creation cursor
     * @param settleIndex provider settlement cursor
     */
    record IncomingLightningInvoiceStatus(
            String status,
            Long receivedSats,
            LocalDateTime settledAt,
            String rawPayload,
            String paymentHash,
            long addIndex,
            long settleIndex) {

        /** Creates a status without provider cursor metadata for legacy callers.
         * @param status normalized invoice state
         * @param receivedSats settled amount in satoshis
         * @param settledAt settlement timestamp
         * @param rawPayload provider response payload
         */
        public IncomingLightningInvoiceStatus(
                String status, Long receivedSats, LocalDateTime settledAt, String rawPayload) {
            this(status, receivedSats, settledAt, rawPayload, null, 0L, 0L);
        }
    }

    /** Input for an outbound on-chain payment.
     * @param userId payment owner
     * @param walletId internal source wallet identifier, when available
     * @param walletName provider wallet name
     * @param destinationAddress recipient address
     * @param amountSats amount to send in satoshis
     * @param description payment description or memo
     * @param idempotencyKey key used to deduplicate retries
     * @param authorizationProof provider-specific proof that the operation was approved
     */
    record OnchainPaymentCommand(
            Long userId,
            Long walletId,
            String walletName,
            String destinationAddress,
            long amountSats,
            String description,
            String idempotencyKey,
            String authorizationProof) {
    }

    /** Input for an outbound Lightning payment.
     * @param userId payment owner
     * @param walletId internal source wallet identifier, when available
     * @param walletName provider wallet name
     * @param paymentRequest encoded Lightning invoice to pay
     * @param amountSats payment amount in satoshis, where amount is not fixed by the invoice
     * @param maxFeeSats maximum routing fee allowed in satoshis
     * @param description payment description or memo
     * @param idempotencyKey key used to deduplicate retries
     * @param authorizationProof provider-specific proof that the operation was approved
     */
    record LightningPaymentCommand(
            Long userId,
            Long walletId,
            String walletName,
            String paymentRequest,
            long amountSats,
            long maxFeeSats,
            String description,
            String idempotencyKey,
            String authorizationProof) {
    }

    /** Normalized outcome of a custodial payment attempt.
     * @param providerReference provider-side payment identifier
     * @param txid on-chain transaction ID, when the payment uses the chain
     * @param paymentHash Lightning payment hash, when the payment uses Lightning
     * @param status normalized provider execution status
     * @param feeSats actual fee charged in satoshis
     * @param rawPayload provider response retained for reconciliation
     */
    record PaymentResult(
            String providerReference,
            String txid,
            String paymentHash,
            String status,
            long feeSats,
            String rawPayload) {
    }
}
