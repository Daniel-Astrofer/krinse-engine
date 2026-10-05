package com.kerosene.kfe.bootstrap.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import com.kerosene.kfe.adapters.out.rail.onchain.BitcoinCoreRpcClient;
import com.kerosene.kfe.wallet.adapters.out.persistence.KfeSystemWalletService;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Performs Bitcoin-related startup checks after Spring has created the application context.
 * System wallets are ensured independently of optional Bitcoin Core RPC readiness; RPC reads
 * use a bounded retry probe and wallet loading occurs only after network and sync policies pass.
 */
@Component
public class KfeBitcoinRuntimeBootstrap implements ApplicationRunner {

    /** Logger for startup outcomes and nonfatal synchronization warnings. */
    private static final Logger log = LoggerFactory.getLogger(KfeBitcoinRuntimeBootstrap.class);

    /** Ensures KFE's local funds and profit wallets exist before serving requests. */
    private final KfeSystemWalletService systemWalletService;
    /** Optional Bitcoin Core RPC adapter, resolved lazily from Spring's provider. */
    private final BitcoinCoreRpcClient bitcoinCoreRpcClient;
    /** Whether startup should contact Bitcoin Core. */
    private final boolean bitcoinRpcEnabled;
    /** Whether missing Bitcoin Core configuration is a startup failure when RPC is enabled. */
    private final boolean bitcoinRpcRequired;
    /** Whether the reported Core chain must match the configured network. */
    private final boolean validateNetwork;
    /** Whether initial block download should block startup. */
    private final boolean requireSynced;
    /** Whether configured RPC wallets should be loaded after readiness checks. */
    private final boolean bootstrapRpcWallets;
    /** Configured Bitcoin network name translated to Bitcoin Core's chain identifier. */
    private final String configuredNetwork;
    /** Optional primary wallet name configured for Bitcoin Core RPC. */
    private final String primaryWalletName;
    /** Funds wallet name to load after readiness checks. */
    private final String fundsWalletName;
    /** Profit wallet name to load after readiness checks. */
    private final String profitWalletName;
    /** Bounded retry helper used only for the blockchain readiness read. */
    private final KfeBitcoinStartupProbe startupProbe;

    /**
     * Spring constructor that binds readiness policies and retry bounds from configuration.
     *
     * @param systemWalletService local system-wallet initializer
     * @param bitcoinCoreRpcClient optional Bitcoin Core adapter provider
     * @param bitcoinRpcEnabled whether the Bitcoin RPC checks are enabled
     * @param bitcoinRpcRequired whether an unavailable RPC adapter must fail startup
     * @param validateNetwork whether to enforce the configured chain name
     * @param requireSynced whether initial block download must be complete
     * @param bootstrapRpcWallets whether to load configured Core wallets after validation
     * @param configuredNetwork configured KFE Bitcoin network
     * @param primaryWalletName optional primary Core wallet
     * @param fundsWalletName Core wallet used for funds
     * @param profitWalletName Core wallet used for profit
     * @param startupMaxAttempts maximum blockchain-info read attempts
     * @param startupRetryDelayMs delay between read attempts in milliseconds
     * @param startupRetryBudgetMs maximum total retry duration in milliseconds
     */
    @Autowired
    public KfeBitcoinRuntimeBootstrap(
            KfeSystemWalletService systemWalletService,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            @Value("${bitcoin.rpc.enabled:false}") boolean bitcoinRpcEnabled,
            @Value("${bitcoin.rpc.required:false}") boolean bitcoinRpcRequired,
            @Value("${kfe.bitcoin.validate-network-enabled:true}") boolean validateNetwork,
            @Value("${kfe.bitcoin.require-synced-enabled:false}") boolean requireSynced,
            @Value("${kfe.bitcoin-core.wallets.bootstrap-enabled:true}") boolean bootstrapRpcWallets,
            @Value("${bitcoin.network:mainnet}") String configuredNetwork,
            @Value("${bitcoin.rpc.wallet:}") String primaryWalletName,
            @Value("${kfe.bitcoin-core.wallets.funds:kerosene-funds}") String fundsWalletName,
            @Value("${kfe.bitcoin-core.wallets.profit:kerosene-profit}") String profitWalletName,
            @Value("${kfe.bitcoin.startup.max-attempts:30}") int startupMaxAttempts,
            @Value("${kfe.bitcoin.startup.retry-delay-ms:2000}") long startupRetryDelayMs,
            @Value("${kfe.bitcoin.startup.retry-budget-ms:60000}") long startupRetryBudgetMs) {
        this(systemWalletService, bitcoinCoreRpcClient, bitcoinRpcEnabled, bitcoinRpcRequired,
                validateNetwork, requireSynced, bootstrapRpcWallets, configuredNetwork,
                primaryWalletName, fundsWalletName, profitWalletName,
                new KfeBitcoinStartupProbe(startupMaxAttempts, startupRetryDelayMs, startupRetryBudgetMs));
    }

    /**
     * Constructor exposing policy and probe dependencies directly for controlled wiring.
     *
     * @param systemWalletService local system-wallet initializer
     * @param bitcoinCoreRpcClient optional RPC adapter provider
     * @param bitcoinRpcEnabled whether RPC validation is enabled
     * @param bitcoinRpcRequired whether an unavailable enabled RPC client is fatal
     * @param validateNetwork whether to compare Core's chain with configuredNetwork
     * @param requireSynced whether initial block download is fatal
     * @param bootstrapRpcWallets whether to load configured Core wallets
     * @param configuredNetwork configured network name
     * @param primaryWalletName optional primary Core wallet name
     * @param fundsWalletName funds wallet name
     * @param profitWalletName profit wallet name
     * @param startupProbe retry/read strategy for blockchain readiness
     */
    KfeBitcoinRuntimeBootstrap(
            KfeSystemWalletService systemWalletService,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            boolean bitcoinRpcEnabled,
            boolean bitcoinRpcRequired,
            boolean validateNetwork,
            boolean requireSynced,
            boolean bootstrapRpcWallets,
            String configuredNetwork,
            String primaryWalletName,
            String fundsWalletName,
            String profitWalletName,
            KfeBitcoinStartupProbe startupProbe) {
        this.systemWalletService = systemWalletService;
        this.bitcoinCoreRpcClient = bitcoinCoreRpcClient.getIfAvailable();
        this.bitcoinRpcEnabled = bitcoinRpcEnabled;
        this.bitcoinRpcRequired = bitcoinRpcRequired;
        this.validateNetwork = validateNetwork;
        this.requireSynced = requireSynced;
        this.bootstrapRpcWallets = bootstrapRpcWallets;
        this.configuredNetwork = configuredNetwork;
        this.primaryWalletName = primaryWalletName;
        this.fundsWalletName = fundsWalletName;
        this.profitWalletName = profitWalletName;
        this.startupProbe = Objects.requireNonNull(startupProbe);
    }

    /**
     * Ensures local wallets, optionally checks Core readiness, and then loads configured RPC wallets.
     * Only the read-only blockchain-info call is retried; wallet-creation and wallet-loading side
     * effects are performed outside the retry loop to avoid repeating mutations.
     *
     * @param args application command-line arguments supplied by Spring Boot
     * @throws IllegalStateException if required Core is unavailable or its readiness policy fails
     */
    @Override
    public void run(ApplicationArguments args) {
        KfeSystemWalletService.SystemWallets systemWallets = systemWalletService.ensureSystemWallets();
        log.info(
                "KFE system wallets ready fundsWalletId={} profitWalletId={}",
                systemWallets.fundsWalletId(),
                systemWallets.profitWalletId());

        if (!bitcoinRpcEnabled) {
            return;
        }
        if (bitcoinCoreRpcClient == null) {
            if (bitcoinRpcRequired) {
                throw new IllegalStateException("bitcoin.rpc.enabled=true but Bitcoin Core RPC client is unavailable.");
            }
            return;
        }

        String expectedChain = validateNetwork ? expectedCoreChain(configuredNetwork) : null;
        // Retry only this read. Local system-wallet and RPC wallet effects are outside the loop.
        JsonNode chainInfo = startupProbe.read(bitcoinCoreRpcClient::blockchainInfo);
        validateBlockchainSnapshot(chainInfo);
        if (expectedChain != null) {
            validateBitcoinCoreNetwork(chainInfo, expectedChain);
        }
        validateBitcoinCoreSyncState(chainInfo);
        if (bootstrapRpcWallets) {
            ensureRpcWalletsLoaded();
        }
    }

    /**
     * Requires a minimally complete blockchain readiness response before inspecting its values.
     *
     * @param chainInfo JSON snapshot returned by Bitcoin Core's blockchain-info RPC
     * @throws IllegalStateException if chain or initial-block-download fields are malformed
     */
    private void validateBlockchainSnapshot(JsonNode chainInfo) {
        if (chainInfo == null || !chainInfo.isObject()
                || !chainInfo.path("chain").isTextual() || chainInfo.path("chain").asText().isBlank()
                || !chainInfo.path("initialblockdownload").isBoolean()) {
            throw new IllegalStateException("Bitcoin Core returned an invalid blockchain readiness snapshot.");
        }
    }

    /**
     * Fails startup when Core reports a different chain than the configured network requires.
     *
     * @param chainInfo validated blockchain readiness response
     * @param expectedChain Core chain identifier derived from configuration
     * @throws IllegalStateException if the actual and expected chain identifiers differ
     */
    private void validateBitcoinCoreNetwork(JsonNode chainInfo, String expectedChain) {
        String actualChain = chainInfo.path("chain").asText();
        if (!expectedChain.equals(actualChain)) {
            throw new IllegalStateException("Bitcoin Core chain mismatch for the configured network.");
        }
    }

    /**
     * Applies the sync policy: fail when synchronization is required, otherwise warn and continue.
     *
     * @param chainInfo validated blockchain readiness response
     * @throws IllegalStateException if Core remains in initial block download and sync is mandatory
     */
    private void validateBitcoinCoreSyncState(JsonNode chainInfo) {
        boolean initialBlockDownload = chainInfo.path("initialblockdownload").booleanValue();
        if (!initialBlockDownload) {
            return;
        }

        long blocks = chainInfo.path("blocks").asLong(-1L);
        long headers = chainInfo.path("headers").asLong(-1L);
        double progress = chainInfo.path("verificationprogress").asDouble(0.0D);
        String message = "Bitcoin Core is still in initial block download"
                + " blocks=" + blocks
                + " headers=" + headers
                + " verificationProgress=" + progress + ".";
        if (requireSynced) {
            throw new IllegalStateException(message);
        }
        log.warn("{} Payment request reconciliation may not observe recent on-chain payments until sync completes.", message);
    }

    /** Loads each distinct configured Core wallet after all readiness checks have passed. */
    private void ensureRpcWalletsLoaded() {
        for (String walletName : walletNames()) {
            bitcoinCoreRpcClient.ensureWalletLoaded(walletName);
            log.info("Bitcoin Core wallet loaded wallet={}", walletName);
        }
    }

    /**
     * Returns configured wallet names in deterministic order with duplicates removed.
     *
     * @return insertion-ordered set of nonblank wallet names
     */
    private Set<String> walletNames() {
        Set<String> wallets = new LinkedHashSet<>();
        addWallet(wallets, primaryWalletName);
        addWallet(wallets, fundsWalletName);
        addWallet(wallets, profitWalletName);
        return wallets;
    }

    /**
     * Adds a wallet name after trimming, omitting null and blank configuration values.
     *
     * @param wallets destination set preserving first-seen order
     * @param walletName configured wallet name, if any
     */
    private void addWallet(Set<String> wallets, String walletName) {
        if (walletName != null && !walletName.isBlank()) {
            wallets.add(walletName.trim());
        }
    }

    /**
     * Maps accepted KFE network aliases to Bitcoin Core's chain identifier.
     *
     * @param network configured network name or alias
     * @return chain name returned by Bitcoin Core
     * @throws IllegalStateException if the configured network is unsupported
     */
    private String expectedCoreChain(String network) {
        String normalized = network != null ? network.trim().toLowerCase(Locale.ROOT) : "";
        return switch (normalized) {
            case "main", "mainnet" -> "main";
            case "testnet", "testnet3" -> "test";
            case "testnet4" -> "testnet4";
            case "signet" -> "signet";
            case "regtest" -> "regtest";
            default -> throw new IllegalStateException("Unsupported bitcoin.network value: " + network);
        };
    }
}
