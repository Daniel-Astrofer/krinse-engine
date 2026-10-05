package com.kerosene.kfe.adapters.out.rail.provider;

import com.kerosene.kfe.adapters.out.rail.custody.BtcPayServerCustodyGateway;
import com.kerosene.kfe.adapters.out.rail.custody.ConfigurableCustodyGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningInvoiceGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningPaymentGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LndRestLightningClient;
import com.kerosene.kfe.adapters.out.rail.onchain.KfeOnchainPaymentGateway;

import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Selects external Lightning invoice/payment providers from configured beans and operator preference.
 * Automatic selection follows the declared LND, BTCPay, configurable-provider priority.
 */
@Configuration("kfeExternalRailProviderConfiguration")
public class ExternalRailProviderConfiguration {

    /** Environment property selecting the incoming Lightning invoice provider. */
    static final String INVOICE_PROVIDER_PROPERTY = "transactions.rails.lightning.invoice-provider";
    /** Environment property selecting the outbound Lightning payment provider. */
    static final String PAYMENT_PROVIDER_PROPERTY = "transactions.rails.lightning.payment-provider";

    /**
     * Registers the selected invoice gateway, using the configured provider key or automatic priority.
     *
     * @param environment Spring property source for invoice provider selection
     * @param lndRestGateway optional LND invoice/payment implementation
     * @param btcpayGateway optional BTCPay invoice implementation
     * @param configurableGateway optional configured custody invoice implementation
     * @return selected Lightning invoice provider
     * @throws IllegalStateException when the requested provider is absent or no automatic candidate exists
     */
    @Bean("kfeExternalLightningInvoiceGateway")
    @ConditionalOnMissingBean(name = "kfeExternalLightningInvoiceGateway")
    public LightningInvoiceGateway kfeExternalLightningInvoiceGateway(
            Environment environment,
            @Qualifier("kfeLndRestLightningClient") ObjectProvider<LndRestLightningClient> lndRestGateway,
            @Qualifier("kfeBtcpayCustodyGateway") ObjectProvider<BtcPayServerCustodyGateway> btcpayGateway,
            @Qualifier("kfeConfigurableCustodyGateway") ObjectProvider<ConfigurableCustodyGateway> configurableGateway) {
        return chooseProvider(
                environment.getProperty(INVOICE_PROVIDER_PROPERTY, "auto"),
                "Lightning invoice",
                List.of(
                        candidate("lnd", lndRestGateway.getIfAvailable()),
                        candidate("btcpay", btcpayGateway.getIfAvailable()),
                        candidate("configurable", configurableGateway.getIfAvailable()),
                        candidate("disabled", new com.kerosene.kfe.adapters.out.rail.lightning.DisabledLightningGateway())));
    }

    /**
     * Registers the selected outbound payment gateway independently of invoice provider selection.
     *
     * @param environment Spring property source for payment provider selection
     * @param lndRestGateway optional LND payment implementation
     * @param btcpayGateway optional BTCPay implementation
     * @param configurableGateway optional configured custody payment implementation
     * @return selected Lightning payment provider
     * @throws IllegalStateException when the requested provider is absent or no automatic candidate exists
     */
    @Bean("kfeExternalLightningPaymentGateway")
    @ConditionalOnMissingBean(name = "kfeExternalLightningPaymentGateway")
    public LightningPaymentGateway kfeExternalLightningPaymentGateway(
            Environment environment,
            @Qualifier("kfeLndRestLightningClient") ObjectProvider<LndRestLightningClient> lndRestGateway,
            @Qualifier("kfeBtcpayCustodyGateway") ObjectProvider<BtcPayServerCustodyGateway> btcpayGateway,
            @Qualifier("kfeConfigurableCustodyGateway") ObjectProvider<ConfigurableCustodyGateway> configurableGateway) {
        return chooseProvider(
                environment.getProperty(PAYMENT_PROVIDER_PROPERTY, "auto"),
                "Lightning payment",
                List.of(
                        candidate("lnd", lndRestGateway.getIfAvailable()),
                        candidate("btcpay", btcpayGateway.getIfAvailable()),
                        candidate("configurable", configurableGateway.getIfAvailable()),
                        candidate("disabled", new com.kerosene.kfe.adapters.out.rail.lightning.DisabledLightningGateway())));
    }

    /**
     * Builds the registry that reports the selected Lightning and on-chain integrations at startup.
     *
     * @param lightningInvoiceGateway selected invoice provider
     * @param lightningPaymentGateway selected outbound Lightning provider
     * @param onchainCustodyPort quorum-backed on-chain payment provider
     * @return provider registry used for application-ready reporting
     */
    @Bean("kfeExternalRailProviderRegistry")
    public ExternalRailProviderRegistry kfeExternalRailProviderRegistry(
            @Qualifier("kfeExternalLightningInvoiceGateway") LightningInvoiceGateway lightningInvoiceGateway,
            @Qualifier("kfeExternalLightningPaymentGateway") LightningPaymentGateway lightningPaymentGateway,
            @Qualifier("bitcoinCorePsbtKfeOnchainPaymentGateway") KfeOnchainPaymentGateway onchainCustodyPort) {
        return new ExternalRailProviderRegistry(
                lightningInvoiceGateway,
                lightningPaymentGateway,
                onchainCustodyPort);
    }

    /**
     * Selects an available candidate by key, or the first nonnull candidate for {@code auto}.
     * Provider keys are trimmed and compared case-insensitively.
     *
     * @param requestedProvider configured key or {@code auto}
     * @param railName human-readable rail name for configuration errors
     * @param candidates ordered candidate list
     * @return selected provider instance
     * @throws IllegalStateException when no candidate is available or the key is unsupported
     */
    static <T> T chooseProvider(
            String requestedProvider,
            String railName,
            List<RailProviderCandidate<T>> candidates) {
        String providerKey = requestedProvider == null || requestedProvider.isBlank()
                ? "auto"
                : requestedProvider.trim().toLowerCase(Locale.ROOT);

        if ("auto".equals(providerKey)) {
            return candidates.stream()
                    .filter(candidate -> candidate.provider() != null)
                    .findFirst()
                    .map(RailProviderCandidate::provider)
                    .orElseThrow(() -> new IllegalStateException(
                            "No provider bean is available for the " + railName + " rail."));
        }

        for (RailProviderCandidate<T> candidate : candidates) {
            if (!candidate.key().equals(providerKey)) {
                continue;
            }
            if (candidate.provider() == null) {
                throw new IllegalStateException(
                        "Configured provider " + providerKey + " is not available for the " + railName + " rail.");
            }
            return candidate.provider();
        }

        String allowedProviders = candidates.stream()
                .map(RailProviderCandidate::key)
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
        throw new IllegalStateException(
                "Unsupported provider " + providerKey + " for the " + railName
                        + " rail. Allowed providers: auto, " + allowedProviders + ".");
    }

    /**
     * Creates a typed candidate entry while preserving provider order for automatic selection.
     *
     * @param key normalized configuration name
     * @param provider optional provider bean
     * @param <T> gateway interface type
     * @return candidate descriptor, including null when a conditional bean is absent
     */
    private static <T> RailProviderCandidate<T> candidate(String key, T provider) {
        return new RailProviderCandidate<>(key, provider);
    }

    /**
     * Candidate pairing a configuration key with an optional bean instance.
     *
     * @param key operator-facing provider key
     * @param provider matching gateway instance, or null when that integration is disabled
     * @param <T> gateway interface type
     */
    record RailProviderCandidate<T>(String key, T provider) {
    }
}
