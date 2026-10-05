package com.kerosene.kfe.bootstrap.config.financial;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import com.kerosene.common.audit.StructuredAuditLogger;
import com.kerosene.common.financial.operations.FinancialMpcKeyPort;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.common.financial.operations.FinancialQuorumPort;
import com.kerosene.common.financial.operations.FinancialTickerPort;
import com.kerosene.common.financial.approval.DeviceProof;
import com.kerosene.common.financial.approval.FinancialTransactionApprovalPort;
import com.kerosene.common.financial.approval.PasskeyAssertion;
import com.kerosene.common.financial.approval.RecoveryApproval;
import com.kerosene.common.financial.operations.FinancialUserDirectoryPort;
import com.kerosene.common.service.AddressDerivationService;
import com.kerosene.kfe.messaging.adapters.out.observability.KfeFinancialNotificationMetrics;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Supplies conditional defaults for financial ports when an embedding application has not provided them.
 * Development-only quorum/notification simulations are profile-gated; production fallbacks fail closed.
 */
@Configuration
public class KfeFinancialFallbackConfiguration {

    /** Safe startup error used when standalone mode cannot provision a real MPC key. */
    private static final String STANDALONE_MPC_UNAVAILABLE =
            "KFE standalone MPC key provisioning is unavailable.";
    /** Logger for activation of development simulations and missing production integrations. */
    private static final Logger log = LoggerFactory.getLogger(KfeFinancialFallbackConfiguration.class);

    /**
     * Provides the common structured audit logger when the host application has not supplied one.
     *
     * @return default structured audit logger
     */
    @Bean
    @ConditionalOnMissingBean(StructuredAuditLogger.class)
    public StructuredAuditLogger kfeStructuredAuditLogger() {
        return new StructuredAuditLogger();
    }

    /**
     * Provides address derivation configured for the selected Bitcoin network and derivation salt.
     *
     * @param network network name from {@code bitcoin.network}
     * @param salt derivation salt from {@code bitcoin.derivation.salt}
     * @return default address derivation service when no host bean exists
     */
    @Bean
    @ConditionalOnMissingBean(AddressDerivationService.class)
    public AddressDerivationService kfeAddressDerivationService(
            @Value("${bitcoin.network:mainnet}") String network,
            @Value("${bitcoin.derivation.salt:kerosene_sovereign_salt_2026}") String salt) {
        return new AddressDerivationService(network, salt);
    }

    /**
     * Supplies deterministic local ticker values for deployments without a ticker adapter.
     * This is a fallback/testing quote source and is not a live market-price feed.
     *
     * @return fixed-value ticker port for USD, EUR, and the default currency branch
     */
    @Bean
    @ConditionalOnMissingBean(FinancialTickerPort.class)
    public FinancialTickerPort kfeFinancialTickerPort() {
        return currency -> {
            if ("usd".equalsIgnoreCase(currency)) {
                return new BigDecimal("65000");
            }
            if ("eur".equalsIgnoreCase(currency)) {
                return new BigDecimal("60000");
            }
            return new BigDecimal("325000");
        };
    }

    /**
     * Dev quorum simulation allowed only in test/local/dev profiles.
     * Production or any non-{test,local,dev} profile must provide a real FinancialQuorumPort
     * implementation (e.g. SovereignFinancialQuorumAdapter), otherwise boot fails.
     */
    /**
     * Registers an auto-approving quorum simulation only in nonproduction profiles.
     *
     * @param memberCount simulated constitution member count
     * @param threshold simulated approval threshold
     * @return development port reporting the configured threshold and member count
     */
    @Bean
    @Profile({"test", "local", "dev"})
    @ConditionalOnMissingBean(FinancialQuorumPort.class)
    public FinancialQuorumPort kfeFinancialQuorumPortDev(
            @Value("${kfe.vaultmesh.constitution.member-count:3}") int memberCount,
            @Value("${kfe.vaultmesh.constitution.threshold:2}") int threshold) {
        log.warn("[KFE Config] DEV QUORUM SIMULATION: all proposals auto-approved "
                + "with {}/{} simulated unanimity. NEVER USE IN PRODUCTION.",
                threshold, memberCount);
        return proposalHash -> new FinancialQuorumPort.Result(threshold, memberCount);
    }

    /**
     * Production / non-dev startup guard: if no real FinancialQuorumPort is registered,
     * fail boot when production mode is enabled to prevent silent 1/1 fallback.
     */
    /**
     * Enforces real quorum configuration outside test/local/dev profiles and otherwise fails closed.
     *
     * @param productionMode whether production authentication mode is enabled
     * @param memberCount configured vault-mesh member count
     * @param threshold configured quorum threshold
     * @return a port that always rejects use when running nonproduction without a real adapter
     * @throws IllegalStateException when production mode lacks a real port or constitution is invalid
     */
    @Bean
    @Profile("!test & !local & !dev")
    @ConditionalOnMissingBean(FinancialQuorumPort.class)
    public FinancialQuorumPort kfeFinancialQuorumPortProductionGuard(
            @Value("${kfe.auth.production-mode:false}") boolean productionMode,
            @Value("${kfe.vaultmesh.constitution.member-count:3}") int memberCount,
            @Value("${kfe.vaultmesh.constitution.threshold:2}") int threshold) {
        if (productionMode) {
            throw new IllegalStateException(
                    "[KFE Config] Production mode enabled (kfe.auth.production-mode=true) "
                    + "but no FinancialQuorumPort implementation found. "
                    + "Required: SovereignFinancialQuorumAdapter (kerosene-app) or equivalent "
                    + "quorum adapter. Refusing to run without real quorum in production.");
        }
        if (memberCount < threshold || threshold < 1 || memberCount < 1) {
            throw new IllegalStateException(
                    "[KFE Config] Invalid vault mesh constitution: "
                    + "memberCount=" + memberCount + ", threshold=" + threshold + ". "
                    + "Requires: memberCount >= threshold >= 1. "
                    + "Set kfe.vaultmesh.constitution.member-count and "
                    + "kfe.vaultmesh.constitution.threshold.");
        }
        log.error("[KFE Config] No FinancialQuorumPort implementation found in non-test profile. "
                + "All vault mesh quorum calls will fail. "
                + "Deploy a real quorum adapter (SovereignFinancialQuorumAdapter) before production.");
        return proposalHash -> {
            throw new IllegalStateException(
                    "[KFE Config] No FinancialQuorumPort implementation found. "
                    + "Mesh quorum proposals are fail-closed. "
                    + "Deploy SovereignFinancialQuorumAdapter for real custody quorum.");
        };
    }

    /**
     * Provides a local deterministic key stub only when explicitly enabled in LOCAL region;
     * all other standalone configurations reject key provisioning.
     *
     * @param devKeygenEnabled explicit local development key-generation switch
     * @param region deployment region label
     * @return guarded development key port
     */
    @Bean
    @ConditionalOnMissingBean(FinancialMpcKeyPort.class)
    public FinancialMpcKeyPort kfeFinancialMpcKeyPort(
            @Value("${kfe.standalone.mpc.dev-keygen-enabled:false}") boolean devKeygenEnabled,
            @Value("${REGION:${region:}}") String region) {
        if (devKeygenEnabled && "LOCAL".equalsIgnoreCase(region)) {
            return (walletId, userId) -> "kfe-local-dev-mpc-public-key:" + walletId + ":" + userId;
        }
        return (walletId, userId) -> {
            throw new IllegalStateException(STANDALONE_MPC_UNAVAILABLE);
        };
    }

    /**
     * Provides an approval port whose every operation rejects because standalone KFE has no
     * transaction-approval authority.
     *
     * @return fail-closed transaction approval implementation
     */
    @Bean
    @ConditionalOnMissingBean(FinancialTransactionApprovalPort.class)
    public FinancialTransactionApprovalPort kfeFinancialTransactionApprovalPort() {
        return new StandaloneUnavailableTransactionApprovalPort();
    }

    /**
     * Noop notification port allowed only in test/local/dev profiles.
     * Production must have a real implementation (e.g. NotificationFinancialNotificationAdapter
     * or KfeRemoteFinancialNotificationClient), otherwise boot fails.
     */
    /**
     * Registers a development-only no-op notification port and marks that fallback active in metrics.
     *
     * @param metrics notification port metrics to expose fallback activation
     * @return no-op notification implementation for nonproduction profiles
     */
    @Bean
    @Profile({"test", "local", "dev"})
    @ConditionalOnMissingBean(FinancialNotificationPort.class)
    public FinancialNotificationPort kfeFinancialNotificationPortNoop(
            KfeFinancialNotificationMetrics metrics) {
        metrics.recordPortActive("noop");
        log.warn("[KFE Config] FinancialNotificationPort fallback (noop) activated — non-production profile");
        return new NoopFinancialNotificationPort();
    }

    /**
     * Production startup guard: if no real FinancialNotificationPort is registered, fail boot.
     * The health endpoint will report readiness DOWN until a real port bean is present.
     */
    /**
     * Refuses production startup when silent notification loss is disallowed; outside production
     * returns a logging, no-op fail-safe until a real notification adapter is installed.
     *
     * @param productionMode whether production authentication mode is enabled
     * @return no-op notification port for a nonproduction profile lacking an adapter
     * @throws IllegalStateException when production mode lacks a real notification port
     */
    @Bean
    @Profile("!test & !local & !dev")
    @ConditionalOnMissingBean(FinancialNotificationPort.class)
    public FinancialNotificationPort kfeFinancialNotificationPortProductionGuard(
            @Value("${kfe.auth.production-mode:false}") boolean productionMode) {
        if (productionMode) {
            throw new IllegalStateException(
                    "[KFE Config] Production mode enabled but no FinancialNotificationPort "
                    + "implementation found. Required: NotificationFinancialNotificationAdapter "
                    + "(kerosene-app) or KfeRemoteFinancialNotificationClient (kfe-service). "
                    + "Refusing to run with silent notification fallback in production.");
        }
        log.error("[KFE Config] No FinancialNotificationPort implementation found in non-test profile. "
                + "Notifications will be silently dropped. Deploy a real adapter before production.");
        return new NoopFinancialNotificationPort();
    }

    /**
     * Provides an empty user directory for standalone contexts without identity integration.
     *
     * @return directory that returns no users for either lookup method
     */
    @Bean
    @ConditionalOnMissingBean(FinancialUserDirectoryPort.class)
    public FinancialUserDirectoryPort kfeFinancialUserDirectoryPort() {
        return new EmptyFinancialUserDirectoryPort();
    }

    /** Standalone fallback that rejects every transaction-approval request. */
    private static final class StandaloneUnavailableTransactionApprovalPort
            implements FinancialTransactionApprovalPort {

        /**
         * Rejects local-factor approval because standalone KFE has no approval authority.
         *
         * @param userId actor requesting approval
         * @param deviceRef registered device reference
         * @param factor local approval factor
         * @throws IllegalStateException always; standalone approval is unavailable
         */
        @Override
        public void approveLocalFactor(Long userId, String deviceRef, DeviceProof factor) {
            throw new IllegalStateException("KFE standalone transaction approval is unavailable.");
        }

        @Override
        public void approveCustodyTransfer(Long userId, PasskeyAssertion assertion) {
            throw new IllegalStateException("KFE standalone transaction approval is unavailable.");
        }

        @Override
        public void approveWalletOutbound(
                Long actorUserId,
                Long ownerUserId,
                PasskeyAssertion passkeyAssertion,
                RecoveryApproval recoveryApproval,
                DeviceProof deviceProof) {
            throw new IllegalStateException("KFE standalone transaction approval is unavailable.");
        }

        @Override
        public void approveColdWalletPsbt(Long userId, DeviceProof factor) {
            throw new IllegalStateException("KFE standalone transaction approval is unavailable.");
        }
    }

    /** Nonproduction notification fallback that deliberately performs no delivery. */
    private static final class NoopFinancialNotificationPort implements FinancialNotificationPort {

        /**
         * No-op implementation of a confirmed deposit notification.
         *
         * @param userId recipient
         * @param transactionId confirmed transaction
         * @param walletId credited wallet
         * @param rail payment rail
         * @param creditedSats credited amount in satoshis
         * @param confirmations observed confirmation count
         */
        @Override
        public void notifyDepositConfirmed(Long userId, UUID transactionId, UUID walletId, String rail,
                                           long creditedSats, int confirmations) { }

        /**
         * No-op implementation of a confirmed deposit linked to a payment request.
         *
         * @param userId recipient
         * @param transactionId confirmed transaction
         * @param paymentRequestId payment request identifier
         * @param publicId public request identifier
         * @param walletId credited wallet
         * @param rail payment rail
         * @param creditedSats credited amount in satoshis
         */
        @Override
        public void notifyPaymentRequestDepositConfirmed(Long userId, UUID transactionId, UUID paymentRequestId,
                                                         String publicId, UUID walletId, String rail,
                                                         long creditedSats) { }

        /**
         * No-op implementation of an initial deposit-detected notification.
         *
         * @param userId recipient
         * @param transactionId detected transaction
         * @param walletId destination wallet
         * @param rail payment rail
         * @param creditedSats expected amount in satoshis
         * @param confirmations observed confirmation count
         */
        @Override
        public void notifyDepositDetected(Long userId, UUID transactionId, UUID walletId, String rail,
                                          long creditedSats, int confirmations) { }

        /**
         * No-op implementation of a deposit confirmation-progress notification.
         *
         * @param userId recipient
         * @param transactionId observed transaction
         * @param walletId destination wallet
         * @param rail payment rail
         * @param creditedSats expected amount in satoshis
         * @param confirmations current confirmation count
         */
        @Override
        public void notifyDepositConfirmationProgress(Long userId, UUID transactionId, UUID walletId, String rail,
                                                      long creditedSats, int confirmations) { }

        /**
         * No-op implementation of an outbound transaction-detected notification.
         *
         * @param userId recipient
         * @param transactionId outbound transaction
         * @param walletId source wallet
         * @param rail payment rail
         * @param amountSats sent amount in satoshis
         * @param confirmations observed confirmation count
         * @param destinationHint sanitized display hint
         */
        @Override
        public void notifyOutboundDetected(Long userId, UUID transactionId, UUID walletId, String rail,
                                           long amountSats, int confirmations, String destinationHint) { }

        /**
         * No-op implementation of a confirmed outbound transaction notification.
         *
         * @param userId recipient
         * @param transactionId outbound transaction
         * @param walletId source wallet
         * @param rail payment rail
         * @param amountSats sent amount in satoshis
         * @param confirmations observed confirmation count
         */
        @Override
        public void notifyOutboundConfirmed(Long userId, UUID transactionId, UUID walletId, String rail,
                                            long amountSats, int confirmations) { }
    }

    /** Standalone user directory that has no identity source and always returns empty lookups. */
    private static final class EmptyFinancialUserDirectoryPort implements FinancialUserDirectoryPort {

        /**
         * Looks up a username in the absent standalone identity directory.
         *
         * @param username exact username requested
         * @return always empty in standalone mode
         */
        @Override
        public Optional<FinancialUserHandle> findByUsername(String username) {
            return Optional.empty();
        }

        /**
         * Looks up a user ID in the absent standalone identity directory.
         *
         * @param userId exact user identifier requested
         * @return always empty in standalone mode
         */
        @Override
        public Optional<FinancialUserHandle> findById(Long userId) {
            return Optional.empty();
        }
    }
}
