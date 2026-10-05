package com.kerosene.kfe.adapters.out.rail.provider;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.util.ClassUtils;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningInvoiceGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningPaymentGateway;
import com.kerosene.kfe.adapters.out.rail.onchain.KfeOnchainPaymentGateway;

/**
 * Reports the selected external rail implementations and their runtime liveness at application startup.
 * Provider inspection is best-effort so a broken Lightning health probe does not prevent boot.
 */
public class ExternalRailProviderRegistry {

    /** Logger used for the sanitized provider summary emitted after application startup. */
    private static final Logger log = LoggerFactory.getLogger(ExternalRailProviderRegistry.class);

    /** Selected gateway for creating and monitoring incoming Lightning invoices. */
    private final LightningInvoiceGateway lightningInvoiceGateway;
    /** Selected gateway for outbound Lightning payments. */
    private final LightningPaymentGateway lightningPaymentGateway;
    /** Selected quorum-backed provider for outbound on-chain payments. */
    private final KfeOnchainPaymentGateway onchainCustodyPort;

    /**
     * Creates the registry with the provider choices made by Spring configuration.
     *
     * @param lightningInvoiceGateway incoming Lightning invoice provider
     * @param lightningPaymentGateway outbound Lightning payment provider
     * @param onchainCustodyPort on-chain payment provider
     */
    public ExternalRailProviderRegistry(
            LightningInvoiceGateway lightningInvoiceGateway,
            LightningPaymentGateway lightningPaymentGateway,
            KfeOnchainPaymentGateway onchainCustodyPort) {
        this.lightningInvoiceGateway = lightningInvoiceGateway;
        this.lightningPaymentGateway = lightningPaymentGateway;
        this.onchainCustodyPort = onchainCustodyPort;
    }

    /**
     * Logs a concise provider summary after the application context is ready.
     * Liveness/provider lookup failures are converted to safe status values before logging.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logActiveProviders() {
        Map<String, RailProviderStatus> providers = activeProviders();
        log.info(
                "[ExternalRails] Active providers: lightningInvoice={} lightningPayment={} onchainOutbound={}",
                providers.get("lightningInvoice").summary(),
                providers.get("lightningPayment").summary(),
                providers.get("onchainOutbound").summary());
    }

    /**
     * Builds an immutable map of provider identity, liveness, and implementation class per rail.
     *
     * @return entries keyed by {@code lightningInvoice}, {@code lightningPayment}, and {@code onchainOutbound}
     */
    public Map<String, RailProviderStatus> activeProviders() {
        Map<String, RailProviderStatus> providers = new LinkedHashMap<>();
        providers.put("lightningInvoice", lightningStatus(lightningInvoiceGateway));
        providers.put("lightningPayment", lightningStatus(lightningPaymentGateway));
        providers.put("onchainOutbound", onchainStatus(onchainCustodyPort));
        return Map.copyOf(providers);
    }

    /**
     * Creates status for the invoice gateway.
     *
     * @param gateway invoice gateway
     * @return safe provider name, liveness, and runtime class
     */
    private RailProviderStatus lightningStatus(LightningInvoiceGateway gateway) {
        return new RailProviderStatus(
                safeProviderName(gateway),
                safeLive(gateway),
                ClassUtils.getUserClass(gateway).getSimpleName());
    }

    /**
     * Creates status for the payment gateway.
     *
     * @param gateway payment gateway
     * @return safe provider name, liveness, and runtime class
     */
    private RailProviderStatus lightningStatus(LightningPaymentGateway gateway) {
        return new RailProviderStatus(
                safeProviderName(gateway),
                safeLive(gateway),
                ClassUtils.getUserClass(gateway).getSimpleName());
    }

    /**
     * Creates status for the on-chain gateway, whose interface has no liveness probe.
     *
     * @param port on-chain gateway
     * @return safe provider name, configured as live, and runtime class
     */
    private RailProviderStatus onchainStatus(KfeOnchainPaymentGateway port) {
        return new RailProviderStatus(
                safeProviderName(port),
                true,
                ClassUtils.getUserClass(port).getSimpleName());
    }

    /**
     * Reads provider identity without allowing a faulty provider to break status reporting.
     *
     * @param gateway invoice provider
     * @return provider name, or UNAVAILABLE if its probe throws
     */
    private String safeProviderName(LightningInvoiceGateway gateway) {
        try {
            return gateway.providerName();
        } catch (RuntimeException exception) {
            return "UNAVAILABLE";
        }
    }

    /**
     * Reads provider identity without allowing a faulty provider to break status reporting.
     *
     * @param gateway payment provider
     * @return provider name, or UNAVAILABLE if its probe throws
     */
    private String safeProviderName(LightningPaymentGateway gateway) {
        try {
            return gateway.providerName();
        } catch (RuntimeException exception) {
            return "UNAVAILABLE";
        }
    }

    /**
     * Reads on-chain provider identity without allowing a faulty provider to break status reporting.
     *
     * @param port on-chain provider
     * @return provider name, or UNAVAILABLE if its probe throws
     */
    private String safeProviderName(KfeOnchainPaymentGateway port) {
        try {
            return port.providerName();
        } catch (RuntimeException exception) {
            return "UNAVAILABLE";
        }
    }

    /**
     * Reads invoice-provider liveness conservatively.
     *
     * @param gateway invoice provider
     * @return liveness, defaulting to false when its probe throws
     */
    private boolean safeLive(LightningInvoiceGateway gateway) {
        try {
            return gateway.isLive();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Reads payment-provider liveness conservatively.
     *
     * @param gateway payment provider
     * @return liveness, defaulting to false when its probe throws
     */
    private boolean safeLive(LightningPaymentGateway gateway) {
        try {
            return gateway.isLive();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Immutable public-facing summary of one selected provider implementation.
     *
     * @param providerName configured provider identity or UNAVAILABLE fallback
     * @param live whether the Lightning gateway reports itself operational
     * @param implementation runtime class name used for diagnostics
     */
    public record RailProviderStatus(
            String providerName,
            boolean live,
            String implementation) {

        /** @return compact provider/class/liveness string for startup logs */
        String summary() {
            return providerName + "/" + implementation + "/" + (live ? "live" : "not-live");
        }
    }
}
