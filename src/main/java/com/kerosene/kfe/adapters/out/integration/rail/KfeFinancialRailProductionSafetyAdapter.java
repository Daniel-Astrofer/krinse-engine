package com.kerosene.kfe.adapters.out.integration.rail;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import com.kerosene.common.financial.operations.FinancialRailProductionSafetyPort;
import com.kerosene.kfe.adapters.out.rail.custody.ConfigurableCustodyGateway;
import com.kerosene.kfe.adapters.out.rail.onchain.KfeOnchainPaymentGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningInvoiceGateway;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningPaymentGateway;

import java.util.ArrayList;
import java.util.List;

/** Checks that production rail wiring uses available live providers and excludes weak configurable gateways. */
@Component
public class KfeFinancialRailProductionSafetyAdapter implements FinancialRailProductionSafetyPort {

    /** Required Spring bean name for the production Lightning invoice provider. */
    private static final String LIGHTNING_INVOICE_BEAN = "kfeExternalLightningInvoiceGateway";
    /** Required Spring bean name for the production Lightning payment provider. */
    private static final String LIGHTNING_PAYMENT_BEAN = "kfeExternalLightningPaymentGateway";
    /** Required Spring bean name for the production on-chain payment provider. */
    private static final String ONCHAIN_BEAN = "bitcoinCorePsbtKfeOnchainPaymentGateway";
    /** Provider identity required for production on-chain sends. */
    private static final String ONCHAIN_PROVIDER_NAME = "BITCOIN_CORE_QUORUM";

    /** Bean registry used to inspect effective production wiring by stable bean name. */
    private final ListableBeanFactory beanFactory;

    /** @param beanFactory Spring bean registry for resolving configured production rail adapters */
    public KfeFinancialRailProductionSafetyAdapter(ListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    /**
     * Collects all detectable production-wiring violations without aborting at the first missing or invalid bean.
     * The result covers presence and liveness of Lightning providers, disallows the weak configurable
     * custody implementation on Lightning, and requires the quorum Bitcoin Core provider for on-chain sends.
     *
     * @return immutable list of human-readable violations; empty means these checks passed
     */
    @Override
    public List<String> collectProductionViolations() {
        List<String> violations = new ArrayList<>();
        LightningInvoiceGateway invoiceGateway = requireBean(
                violations,
                LIGHTNING_INVOICE_BEAN,
                LightningInvoiceGateway.class,
                "Lightning invoice rail");
        if (invoiceGateway != null) {
            inspectLightningGateway(violations, "Lightning invoice rail", invoiceGateway);
        }

        LightningPaymentGateway paymentGateway = requireBean(
                violations,
                LIGHTNING_PAYMENT_BEAN,
                LightningPaymentGateway.class,
                "Lightning payment rail");
        if (paymentGateway != null) {
            inspectLightningGateway(violations, "Lightning payment rail", paymentGateway);
        }

        KfeOnchainPaymentGateway onchainPort = requireBean(
                violations,
                ONCHAIN_BEAN,
                KfeOnchainPaymentGateway.class,
                "On-chain outbound rail");
        if (onchainPort != null && !ONCHAIN_PROVIDER_NAME.equalsIgnoreCase(safeProviderName(onchainPort))) {
            violations.add("On-chain outbound rail must use " + ONCHAIN_PROVIDER_NAME + " in prod");
        }
        return List.copyOf(violations);
    }

    /** Resolves a required provider bean and records a violation instead of propagating lookup failures. */
    private <T> T requireBean(
            List<String> violations,
            String beanName,
            Class<T> beanType,
            String railName) {
        try {
            if (!beanFactory.containsBean(beanName)) {
                violations.add(railName + " provider bean " + beanName + " must be available in prod");
                return null;
            }
            return beanFactory.getBean(beanName, beanType);
        } catch (RuntimeException exception) {
            violations.add(railName + " provider bean " + beanName + " could not be verified");
            return null;
        }
    }

    /** Records weak implementation and non-live status violations for one Lightning provider. */
    private void inspectLightningGateway(
            List<String> violations,
            String railName,
            Object gateway) {
        if (gateway instanceof com.kerosene.kfe.adapters.out.rail.lightning.DisabledLightningGateway) return;
        if (isWeakConfigurableGateway(gateway)) {
            violations.add(railName + " must not use configurable custody gateway in prod");
        }
        if (!safeLive(gateway)) {
            violations.add(railName + " provider must be live in prod");
        }
    }

    /** Identifies configurable or legacy BCX gateways that are not permitted for production Lightning. */
    private boolean isWeakConfigurableGateway(Object gateway) {
        Class<?> userClass = ClassUtils.getUserClass(gateway);
        return ConfigurableCustodyGateway.class.isAssignableFrom(userClass)
                || "ConfigurableCustodyGateway".equals(userClass.getSimpleName())
                || "BCX".equalsIgnoreCase(safeProviderName(gateway));
    }

    /** Reads a Lightning gateway liveness flag and treats provider exceptions or unsupported types as not live. */
    private boolean safeLive(Object gateway) {
        try {
            if (gateway instanceof LightningInvoiceGateway invoiceGateway) {
                return invoiceGateway.isLive();
            }
            if (gateway instanceof LightningPaymentGateway paymentGateway) {
                return paymentGateway.isLive();
            }
            return false;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** Reads a provider identity from a supported rail port and returns empty when it cannot be obtained safely. */
    private String safeProviderName(Object gateway) {
        try {
            if (gateway instanceof LightningInvoiceGateway invoiceGateway) {
                return invoiceGateway.providerName();
            }
            if (gateway instanceof LightningPaymentGateway paymentGateway) {
                return paymentGateway.providerName();
            }
            if (gateway instanceof KfeOnchainPaymentGateway custodyPort) {
                return custodyPort.providerName();
            }
            return "";
        } catch (RuntimeException exception) {
            return "";
        }
    }
}
