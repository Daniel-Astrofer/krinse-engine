package com.kerosene.kfe.paymentrequest.adapters.out.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the KFE webhook delivery subsystem.
 *
 * <pre>
 * kfe.webhook.enabled=false
 * kfe.webhook.signing-secret=${KFE_WEBHOOK_SECRET:}
 * kfe.webhook.max-retries=3
 * kfe.webhook.timeout-seconds=10
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "kfe.webhook")
public class KfeWebhookConfig {

    /** Master enable/disable switch. Default disabled for production safety. */
    private boolean enabled = false;

    /** HMAC-SHA256 secret for request signing. Must be set when enabled. */
    private String signingSecret = "";

    /** Number of retry attempts after initial failure (total attempts = maxRetries + 1). */
    private int maxRetries = 3;

    /** Per-request connect + read timeout in seconds. */
    private int timeoutSeconds = 10;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getSigningSecret() { return signingSecret; }
    public void setSigningSecret(String signingSecret) { this.signingSecret = signingSecret; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
