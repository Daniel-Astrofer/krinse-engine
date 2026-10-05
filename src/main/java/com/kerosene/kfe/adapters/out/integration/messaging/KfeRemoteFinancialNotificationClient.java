package com.kerosene.kfe.adapters.out.integration.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.kerosene.common.security.workload.InternalServiceRestTemplateFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import com.kerosene.common.financial.notification.FinancialDepositConfirmedNotificationRequest;
import com.kerosene.common.financial.notification.FinancialExternalPaymentNotificationRequest;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.common.financial.notification.FinancialOutboundNotificationRequest;
import com.kerosene.common.financial.notification.FinancialPaymentRequestDepositConfirmedNotificationRequest;

import java.util.UUID;
import com.kerosene.common.financial.notification.FinancialNotificationEventRequest;
import com.kerosene.common.financial.notification.FinancialNotificationReceipt;

/** Sends internal KFE payment and deposit events to the core service's notification API. */
@Component
@Profile("kfe")
@ConditionalOnProperty(name = "kfe.remote.notifications.enabled", havingValue = "true", matchIfMissing = true)
public class KfeRemoteFinancialNotificationClient implements FinancialNotificationPort {

    /** Logger used when push delivery fails but financial processing is allowed to continue. */
    private static final Logger log = LoggerFactory.getLogger(KfeRemoteFinancialNotificationClient.class);
    private static final String DEFAULT_BASE_URL = "http://server:8080";

    /** HTTP client configured with bounded connection and response timeouts. */
    private final RestTemplate restTemplate;
    /** Normalized core service base URL with at most one slash before endpoint paths. */
    private final String baseUrl;

    /**
     * Creates the notification client using the shared core-service URL.
     *
     * @param restTemplateFactory factory producing mTLS/SPIFFE or legacy client
     * @param baseUrl optional core-service base URL
     * @param connectTimeoutMs connection timeout in milliseconds
     * @param readTimeoutMs response-read timeout in milliseconds
     */
    public KfeRemoteFinancialNotificationClient(
            InternalServiceRestTemplateFactory restTemplateFactory,
            @Value("${auth.remote.base-url:http://server:8080}") String baseUrl,
            @Value("${auth.remote.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${auth.remote.read-timeout-ms:5000}") long readTimeoutMs) {
        InternalServiceRestTemplateFactory.ConfiguredClient client = restTemplateFactory.create(
                baseUrl, DEFAULT_BASE_URL, connectTimeoutMs, readTimeoutMs);
        this.restTemplate = client.restTemplate();
        this.baseUrl = client.baseUrl();
    }

    /** Strict delivery for the durable worker; any absent or mismatched receipt is retryable. */
    @Override
    public FinancialNotificationReceipt deliverEvent(FinancialNotificationEventRequest event) {
        var response = restTemplate.postForEntity(baseUrl + "/internal/kfe/notifications/events",
                internalJsonEntity(event), FinancialNotificationReceipt.class);
        var receipt = response.getBody();
        if (!response.getStatusCode().is2xxSuccessful() || receipt == null || !receipt.accepted()
                || !event.eventId().equals(receipt.eventId())) {
            throw new IllegalStateException("Financial event was not accepted durably.");
        }
        return receipt;
    }

    /**
     * Notifies the user that an inbound deposit reached its credited confirmation state.
     * Delivery is best-effort after request construction; network and remote response errors are logged.
     *
     * @param userId credited account owner
     * @param transactionId ledger transaction associated with the deposit
     * @param walletId receiving wallet
     * @param rail deposit rail, such as on-chain or Lightning
     * @param creditedSats amount credited in satoshis
     * @param confirmations observed confirmation count
     */
    @Override
    public void notifyDepositConfirmed(
            Long userId,
            UUID transactionId,
            UUID walletId,
            String rail,
            long creditedSats,
            int confirmations) {
        post("/internal/kfe/notifications/deposit-confirmed",
                new FinancialDepositConfirmedNotificationRequest(
                        userId,
                        transactionId,
                        walletId,
                        rail,
                        creditedSats,
                        confirmations));
    }

    /** Notifies the owner that a deposit satisfied a public payment request.
     * @param userId merchant or request owner
     * @param transactionId confirmed deposit transaction
     * @param paymentRequestId persisted payment request identifier
     * @param publicId externally shareable payment request identifier
     * @param walletId receiving wallet
     * @param rail deposit rail used
     * @param creditedSats credited amount in satoshis
     */
    @Override
    public void notifyPaymentRequestDepositConfirmed(
            Long userId,
            UUID transactionId,
            UUID paymentRequestId,
            String publicId,
            UUID walletId,
            String rail,
            long creditedSats) {
        post("/internal/kfe/notifications/payment-request-deposit-confirmed",
                new FinancialPaymentRequestDepositConfirmedNotificationRequest(
                        userId,
                        transactionId,
                        paymentRequestId,
                        publicId,
                        walletId,
                        rail,
                        creditedSats));
    }

    /** Notifies the user that an inbound deposit was detected before it reached final confirmation.
     * @param userId deposit owner
     * @param transactionId detected deposit transaction
     * @param walletId receiving wallet
     * @param rail deposit rail
     * @param creditedSats observed amount in satoshis
     * @param confirmations confirmations observed at detection time
     */
    @Override
    public void notifyDepositDetected(
            Long userId,
            UUID transactionId,
            UUID walletId,
            String rail,
            long creditedSats,
            int confirmations) {
        post("/internal/kfe/notifications/deposit-detected",
                new FinancialDepositConfirmedNotificationRequest(
                        userId,
                        transactionId,
                        walletId,
                        rail,
                        creditedSats,
                        confirmations));
    }

    /** Reports updated confirmation progress for a previously detected deposit.
     * @param userId deposit owner
     * @param transactionId deposit transaction
     * @param walletId receiving wallet
     * @param rail deposit rail
     * @param creditedSats expected credited amount in satoshis
     * @param confirmations latest confirmation count
     */
    @Override
    public void notifyDepositConfirmationProgress(
            Long userId,
            UUID transactionId,
            UUID walletId,
            String rail,
            long creditedSats,
            int confirmations) {
        post("/internal/kfe/notifications/deposit-progress",
                new FinancialDepositConfirmedNotificationRequest(
                        userId,
                        transactionId,
                        walletId,
                        rail,
                        creditedSats,
                        confirmations));
    }

    /** Notifies the user that an outgoing transfer has been observed, including a redacted destination hint.
     * @param userId transfer owner
     * @param transactionId outgoing ledger transaction
     * @param walletId funding wallet
     * @param rail payment rail
     * @param amountSats transfer amount in satoshis
     * @param confirmations observed chain confirmations, when applicable
     * @param destinationHint sanitized destination or transaction reference for display
     */
    @Override
    public void notifyOutboundDetected(
            Long userId,
            UUID transactionId,
            UUID walletId,
            String rail,
            long amountSats,
            int confirmations,
            String destinationHint) {
        post("/internal/kfe/notifications/outbound-detected",
                new FinancialOutboundNotificationRequest(
                        userId,
                        transactionId,
                        walletId,
                        rail,
                        amountSats,
                        confirmations,
                        destinationHint));
    }

    /** Notifies the user that an outgoing transfer reached its confirmed state.
     * @param userId transfer owner
     * @param transactionId outgoing ledger transaction
     * @param walletId funding wallet
     * @param rail payment rail
     * @param amountSats transfer amount in satoshis
     * @param confirmations final observed confirmation count
     */
    @Override
    public void notifyOutboundConfirmed(
            Long userId,
            UUID transactionId,
            UUID walletId,
            String rail,
            long amountSats,
            int confirmations) {
        post("/internal/kfe/notifications/outbound-confirmed",
                new FinancialOutboundNotificationRequest(
                        userId,
                        transactionId,
                        walletId,
                        rail,
                        amountSats,
                        confirmations,
                        null));
    }

    /** Notifies the receiving user about an internal KFE transfer.
     * @param receiverUserId recipient account
     * @param transactionId internal transfer transaction
     * @param walletId wallet credited by the transfer
     * @param amountSats received amount in satoshis
     */
    @Override
    public void notifyInternalTransferReceived(
            Long receiverUserId,
            UUID transactionId,
            UUID walletId,
            long amountSats) {
        post("/internal/kfe/notifications/internal-transfer-received",
                new com.kerosene.common.financial.notification.FinancialInternalTransferNotificationRequest(
                        receiverUserId,
                        transactionId,
                        walletId,
                        amountSats));
    }

    /** Notifies the sending user about an internal KFE transfer.
     * @param senderUserId source account
     * @param transactionId internal transfer transaction
     * @param walletId wallet debited by the transfer
     * @param amountSats sent amount in satoshis
     */
    @Override
    public void notifyInternalTransferSent(
            Long senderUserId,
            UUID transactionId,
            UUID walletId,
            long amountSats) {
        post("/internal/kfe/notifications/internal-transfer-sent",
                new com.kerosene.common.financial.notification.FinancialInternalTransferNotificationRequest(
                        senderUserId,
                        transactionId,
                        walletId,
                        amountSats));
    }

    /** Notifies the user that an external payment was sent over the selected financial rail.
     * @param userId payment owner
     * @param transactionId outgoing transaction
     * @param walletId debited wallet
     * @param rail external rail used for settlement
     * @param amountSats amount sent in satoshis
     */
    @Override
    public void notifyExternalPaymentSent(
            Long userId,
            UUID transactionId,
            UUID walletId,
            String rail,
            long amountSats) {
        post("/internal/kfe/notifications/external-payment-sent",
                new com.kerosene.common.financial.notification.FinancialExternalPaymentNotificationRequest(
                        userId,
                        transactionId,
                        walletId,
                        rail,
                        amountSats));
    }

    /** Reports that payment execution has started but is not yet known to be broadcast or settled.
     * @param userId payment owner
     * @param transactionId outgoing transaction
     * @param walletId debited wallet
     * @param rail selected payment rail
     * @param amountSats requested amount in satoshis
     */
    @Override
    public void notifyPaymentInitiated(
            Long userId,
            UUID transactionId,
            UUID walletId,
            String rail,
            long amountSats) {
        post("/internal/kfe/notifications/payment-initiated",
                new FinancialExternalPaymentNotificationRequest(
                        userId, transactionId, walletId, rail, amountSats));
    }

    /** Reports broadcast and carries the transaction ID as the outbound destination hint.
     * @param userId payment owner
     * @param transactionId outgoing ledger transaction
     * @param walletId debited wallet
     * @param rail payment rail
     * @param amountSats amount submitted in satoshis
     * @param txid broadcast transaction ID
     */
    @Override
    public void notifyPaymentBroadcast(
            Long userId,
            UUID transactionId,
            UUID walletId,
            String rail,
            long amountSats,
            String txid) {
        post("/internal/kfe/notifications/payment-broadcast",
                new FinancialOutboundNotificationRequest(
                        userId, transactionId, walletId, rail, amountSats, 0, txid));
    }

    /** Reports successful confirmation with the confirmation count observed by the rail adapter.
     * @param userId payment owner
     * @param transactionId outgoing ledger transaction
     * @param walletId debited wallet
     * @param rail payment rail
     * @param amountSats amount settled in satoshis
     * @param confirmations observed confirmation count
     */
    @Override
    public void notifyPaymentConfirmed(
            Long userId,
            UUID transactionId,
            UUID walletId,
            String rail,
            long amountSats,
            int confirmations) {
        post("/internal/kfe/notifications/payment-confirmed",
                new FinancialOutboundNotificationRequest(
                        userId, transactionId, walletId, rail, amountSats, confirmations, null));
    }

    /** Reports an unsuccessful payment; the failure code is carried as the notification hint.
     * @param userId payment owner
     * @param transactionId failed outgoing transaction
     * @param walletId debited wallet
     * @param rail payment rail
     * @param amountSats attempted amount in satoshis
     * @param failureCode machine-readable cause sent as the outbound hint
     * @param failureMessage human-readable diagnostic currently not serialized by this adapter
     */
    @Override
    public void notifyPaymentFailed(
            Long userId,
            UUID transactionId,
            UUID walletId,
            String rail,
            long amountSats,
            String failureCode,
            String failureMessage) {
        post("/internal/kfe/notifications/payment-failed",
                new FinancialOutboundNotificationRequest(
                        userId, transactionId, walletId, rail, amountSats, 0, failureCode));
    }

    /** Reports that the payment outcome requires operator or automated reconciliation.
     * @param userId payment owner
     * @param transactionId payment under reconciliation
     * @param walletId associated wallet
     * @param rail payment rail
     * @param amountSats attempted amount in satoshis
     * @param reason reconciliation reason sent as the notification hint
     */
    @Override
    public void notifyPaymentReconciliationRequired(
            Long userId,
            UUID transactionId,
            UUID walletId,
            String rail,
            long amountSats,
            String reason) {
        post("/internal/kfe/notifications/payment-reconciliation-required",
                new FinancialOutboundNotificationRequest(
                        userId, transactionId, walletId, rail, amountSats, 0, reason));
    }

    /** Reports that an outgoing transaction conflicts with the expected transaction state.
     * @param userId payment owner
     * @param transactionId conflicted ledger transaction
     * @param walletId source wallet
     * @param rail payment rail
     * @param amountSats attempted amount in satoshis
     * @param txid conflicting transaction identifier or reference
     */
    @Override
    public void notifyOutboundConflicted(
            Long userId,
            UUID transactionId,
            UUID walletId,
            String rail,
            long amountSats,
            String txid) {
        post("/internal/kfe/notifications/outbound-conflicted",
                new FinancialOutboundNotificationRequest(
                        userId, transactionId, walletId, rail, amountSats, 0, txid));
    }

    /**
     * Posts an internal notification and logs delivery failure without undoing the financial operation.
     *
     * @param path internal notification route appended to {@link #baseUrl}
     * @param request typed notification payload serialized by Spring's HTTP converters
     */
    private void post(String path, Object request) {
        HttpEntity<Object> entity = internalJsonEntity(request);
        try {
            restTemplate.postForEntity(baseUrl + path, entity, Void.class);
        } catch (RestClientResponseException exception) {
            log.warn(
                    "[KFE Notify] auth server rejected {} with HTTP {} — continuing without push",
                    path,
                    exception.getStatusCode().value());
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Notify] failed to POST {} ({}): {} — continuing without push",
                    path,
                    exception.getClass().getSimpleName(),
                    exception.getMessage());
        }
    }

    /** Creates a JSON request entity. */
    private <T> HttpEntity<T> internalJsonEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
