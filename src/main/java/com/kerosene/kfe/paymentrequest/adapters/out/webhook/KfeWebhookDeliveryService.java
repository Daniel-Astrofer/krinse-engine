package com.kerosene.kfe.paymentrequest.adapters.out.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Non-blocking webhook delivery with HMAC-SHA256 signing and exponential-backoff retry.
 *
 * <p>Webhooks fire after the payment transaction commits. Delivery failures are logged
 * and discarded after max retries — they never block the payment confirmation path.
 */
@Service
@ConditionalOnProperty(name = "kfe.webhook.enabled", havingValue = "true")
public class KfeWebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(KfeWebhookDeliveryService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_HEADER = "X-Kerosene-Signature";
    private static final String EVENT_ID_HEADER = "X-Kerosene-Event-Id";

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final String signingSecret;
    private final int maxRetries;
    private final Duration timeout;

    public KfeWebhookDeliveryService(
            ObjectMapper objectMapper,
            RestTemplateBuilder restTemplateBuilder,
            KfeWebhookConfig config) {
        this.objectMapper = objectMapper;
        this.signingSecret = config.getSigningSecret() != null ? config.getSigningSecret() : "";
        this.maxRetries = Math.max(0, config.getMaxRetries());
        this.timeout = Duration.ofSeconds(Math.max(1, config.getTimeoutSeconds()));
        this.restTemplate = restTemplateBuilder
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .build();
    }

    /**
     * Enqueue a webhook delivery after the current transaction commits.
     * If no transaction is active, delivers immediately in a fire-and-forget future.
     */
    public void publishAfterCommit(String webhookUrl, KfeWebhookPayload payload) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }
        if (signingSecret.isBlank()) {
            log.warn("[KFE Webhook] signing secret is empty; webhook disabled. Set kfe.webhook.signing-secret.");
            return;
        }

        Runnable delivery = () -> deliverAsync(webhookUrl, payload);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delivery.run();
                }
            });
        } else {
            delivery.run();
        }
    }

    private void deliverAsync(String webhookUrl, KfeWebhookPayload payload) {
        CompletableFuture.runAsync(() -> deliverWithRetry(webhookUrl, payload));
    }

    private void deliverWithRetry(String webhookUrl, KfeWebhookPayload payload) {
        String signedBody;
        try {
            signedBody = signAndSerialize(payload);
        } catch (Exception e) {
            log.error("[KFE Webhook] failed to serialize payload eventId={}", payload.eventId(), e);
            return;
        }

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                long backoffMs = (long) Math.pow(2, attempt - 1) * 1000L;
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set(SIGNATURE_HEADER, computeHmac(signedBody));
                headers.set(EVENT_ID_HEADER, payload.eventId().toString());

                restTemplate.postForEntity(webhookUrl, new HttpEntity<>(signedBody, headers), Void.class);
                log.info("[KFE Webhook] delivered eventId={} type={} attempt={}",
                        payload.eventId(), payload.eventType(), attempt + 1);
                return;
            } catch (RestClientResponseException e) {
                log.warn("[KFE Webhook] rejected eventId={} type={} attempt={}/{} status={}",
                        payload.eventId(), payload.eventType(), attempt + 1, maxRetries + 1,
                        e.getStatusCode().value());
            } catch (Exception e) {
                log.warn("[KFE Webhook] failed eventId={} type={} attempt={}/{}: {}",
                        payload.eventId(), payload.eventType(), attempt + 1, maxRetries + 1,
                        e.getMessage());
            }
        }
        log.error("[KFE Webhook] exhausted retries eventId={} type={}",
                payload.eventId(), payload.eventType());
    }

    private String signAndSerialize(KfeWebhookPayload payload) throws JsonProcessingException {
        // Build the JSON without the signature field, then sign it.
        KfeWebhookPayload unsigned = new KfeWebhookPayload(
                payload.eventId(),
                payload.eventType(),
                payload.timestamp(),
                payload.paymentRequestPublicId(),
                payload.amountSats(),
                payload.status(),
                null);
        return objectMapper.writeValueAsString(unsigned);
    }

    private String computeHmac(String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    signingSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            return Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 not available", e);
        }
    }

    /**
     * Build a webhook payload for a payment event (used during payment confirmation).
     */
    public KfeWebhookPayload buildPayload(
            KfeWebhookEvent eventType,
            String paymentRequestPublicId,
            Long amountSats,
            String status) {
        return new KfeWebhookPayload(
                UUID.randomUUID(),
                eventType,
                Instant.now(),
                paymentRequestPublicId,
                amountSats,
                status,
                null);
    }
}
