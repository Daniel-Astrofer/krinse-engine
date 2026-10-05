package com.kerosene.kfe.ledger.adapters.in.reconciliation;

import com.kerosene.kfe.ledger.adapters.out.observability.KfeBalanceMetrics;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.messaging.adapters.out.websocket.KfeDashboardPublisher;
import com.kerosene.kfe.wallet.adapters.out.bitcoin.KfeWalletDescriptorResolver;
import com.kerosene.kfe.wallet.domain.model.ChainProbeResult;
import com.kerosene.kfe.wallet.domain.model.ProbeQuality;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletAddressEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.adapters.out.rail.onchain.BlockchainClient;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletAddressRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Keeps {@code observed_sats} aligned with the blockchain for on-chain custody kinds.
 *
 * <ul>
 *   <li>{@link KfeWalletKind#WATCH_ONLY} — cold wallet: only chain balance is meaningful.
 *       Writes are gated by {@link ChainProbeResult} quality (Electrum mempool-aware preferred).</li>
 *   <li>{@link KfeWalletKind#CUSTODIAL_ONCHAIN} — dual model: internal ledger
 *       (available/locked) authorizes spends; {@code observed_sats} mirrors chain for
 *       reconciliation/display.</li>
 *   <li>{@link KfeWalletKind#INTERNAL} — not scanned (pooled / ledger-only).</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "kfe.onchain-balance-sync.enabled", havingValue = "true", matchIfMissing = true)
public class KfeOnchainBalanceSyncService {

    /** Logger for probe failures and accepted/deferred observation decisions. */
    private static final Logger log = LoggerFactory.getLogger(KfeOnchainBalanceSyncService.class);
    /** Asset code used for the on-chain BTC observed balance. */
    private static final String ASSET_BTC = "BTC";
    /** Scheduled probe kinds. WATCH_ONLY is refreshed by cold-observation (+ ZMQ) to avoid dual scantxoutset thrash. */
    /** Wallet kinds refreshed by the recurring full-sync schedule. */
    private static final List<KfeWalletKind> SCHEDULED_SYNC_KINDS = List.of(
            KfeWalletKind.CUSTODIAL_ONCHAIN);
    /** Wallet kinds for which direct {@link #syncWallet(UUID)} requests are permitted. */
    private static final List<KfeWalletKind> CHAIN_SYNC_KINDS = List.of(
            KfeWalletKind.WATCH_ONLY,
            KfeWalletKind.CUSTODIAL_ONCHAIN);

    /** Wallet metadata source for active wallet selection and ownership. */
    private final KfeWalletRepository walletRepository;
    /** Ordered address source for per-address chain scans. */
    private final KfeWalletAddressRepository addressRepository;
    /** Transactional ledger boundary for locking and updating observed balances. */
    private final KfeBalanceService balanceService;
    /** Optional Bitcoin Core probe provider. */
    private final ObjectProvider<BlockchainClient> blockchainClient;
    /** Publishes dashboard state after the balance update commits. */
    private final KfeDashboardPublisher dashboardPublisher;
    /** Transaction wrapper keeping slow RPC reads outside row-locking transactions. */
    private final TransactionTemplate transactionTemplate;
    /** Resolves receive descriptors and their change counterparts. */
    private final KfeWalletDescriptorResolver descriptorResolver;
    /** Optional metrics provider for accepted and deferred probes. */
    private final ObjectProvider<KfeBalanceMetrics> balanceMetrics;
    /** Maximum number of custodial wallets handled by one scheduled pass. */
    private final int batchSize;
    /** Configured descriptor scan range, clamped to at least one. */
    private final int descriptorRange;
    /**
     * Fresh LIVE_MEMPOOL_AWARE probes are protected from OPTIMISTIC_DELTA for this many seconds
     * (ZMQ must not overwrite a just-completed full Electrum-parity collect).
     */
    private final long optimisticLiveTtlSeconds;

    /**
     * Creates the sync service and clamps its batch/range/TTL settings to supported minimums.
     *
     * @param walletRepository wallet metadata adapter
     * @param addressRepository wallet address adapter
     * @param balanceService transactional balance service
     * @param blockchainClient optional chain RPC client provider
     * @param dashboardPublisher after-commit dashboard publisher
     * @param transactionTemplate transaction boundary for balance writes
     * @param descriptorResolver wallet descriptor resolver
     * @param balanceMetrics optional probe metrics provider
     * @param batchSize maximum scheduled wallets per pass
     * @param descriptorRange initial descriptor lookahead range
     * @param optimisticLiveTtlSeconds protection window for recent full-quality cold observations
     */
    public KfeOnchainBalanceSyncService(
            KfeWalletRepository walletRepository,
            KfeWalletAddressRepository addressRepository,
            KfeBalanceService balanceService,
            ObjectProvider<BlockchainClient> blockchainClient,
            KfeDashboardPublisher dashboardPublisher,
            TransactionTemplate transactionTemplate,
            KfeWalletDescriptorResolver descriptorResolver,
            ObjectProvider<KfeBalanceMetrics> balanceMetrics,
            @Value("${kfe.onchain-balance-sync.batch-size:50}") int batchSize,
            @Value("${kfe.onchain-balance-sync.descriptor-range:1000}") int descriptorRange,
            @Value("${kfe.onchain-balance-sync.optimistic-live-ttl-seconds:120}") long optimisticLiveTtlSeconds) {
        this.walletRepository = walletRepository;
        this.addressRepository = addressRepository;
        this.balanceService = balanceService;
        this.blockchainClient = blockchainClient;
        this.dashboardPublisher = dashboardPublisher;
        this.transactionTemplate = transactionTemplate;
        this.descriptorResolver = descriptorResolver;
        this.balanceMetrics = balanceMetrics;
        this.batchSize = Math.max(1, batchSize);
        this.descriptorRange = Math.max(1, descriptorRange);
        this.optimisticLiveTtlSeconds = Math.max(0L, optimisticLiveTtlSeconds);
    }

    /** Runs one bounded pass over active custodial wallets when Bitcoin Core is available. */
    @Scheduled(
            fixedDelayString = "${kfe.onchain-balance-sync.fixed-delay-ms:45000}",
            initialDelayString = "${kfe.onchain-balance-sync.initial-delay-ms:25000}")
    public void reconcileActiveOnchainWallets() {
        BlockchainClient client = blockchainClient.getIfAvailable();
        if (client == null) {
            return;
        }
        List<KfeWalletEntity> wallets = walletRepository
                .findByKindInAndStatus(SCHEDULED_SYNC_KINDS, KfeWalletStatus.ACTIVE);
        int limit = Math.min(batchSize, wallets.size());
        for (int i = 0; i < limit; i++) {
            try {
                syncWallet(wallets.get(i).getId());
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Onchain Balance] sync failed walletId={}: {}",
                        wallets.get(i).getId(),
                        exception.getMessage());
            }
        }
    }

    /**
     * Probes the chain and writes absolute {@code observed_sats} when the wallet kind permits it.
     *
     * <p>Chain RPC runs <strong>outside</strong> a DB transaction; only the balance write uses
     * {@link TransactionTemplate} (FOR UPDATE). Holding Hikari connections across scantxoutset
     * starved the API and readiness probes.
     *
     * <p>For {@link KfeWalletKind#WATCH_ONLY}, descriptor-only probes are quality
     * {@link ProbeQuality#CONFIRMED_UTXO_SET} and will not overwrite a non-zero previous
     * observed (cold live path owns Electrum parity).
     *
     * @param walletId wallet whose chain balance should be refreshed
     * @return written satoshi balance, zero for unsupported kinds, or {@code -1} when unavailable/failed
     * @throws IllegalArgumentException if the wallet does not exist
     */
    public long syncWallet(UUID walletId) {
        KfeWalletEntity wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        if (!CHAIN_SYNC_KINDS.contains(wallet.getKind())) {
            return 0L;
        }
        BlockchainClient client = blockchainClient.getIfAvailable();
        if (client == null) {
            log.debug("[KFE Onchain Balance] blockchain client unavailable walletId={}", walletId);
            return -1L;
        }

        final long chainSats;
        try {
            chainSats = probeChainBalanceSats(client, wallet);
        } catch (RuntimeException exception) {
            // Never write a partial/failed probe — that is what made cold balances oscillate.
            log.warn(
                    "[KFE Onchain Balance] probe failed walletId={} — keeping previous observed: {}",
                    walletId,
                    exception.getMessage());
            return -1L;
        }

        ProbeQuality quality = ProbeQuality.CONFIRMED_UTXO_SET;
        boolean authoritative = wallet.getKind() != KfeWalletKind.WATCH_ONLY;
        ChainProbeResult probe = new ChainProbeResult(
                chainSats,
                quality,
                authoritative,
                0,
                "syncWallet-descriptor");
        return applyObserved(walletId, probe);
    }

    /**
     * Applies a legacy absolute amount as a confirmed-UTXO observation with a compatibility source label.
     * For watch-only wallets this can seed an empty balance but cannot replace an existing nonzero live value.
     * Prefer {@link #applyObserved(UUID, ChainProbeResult)} when quality metadata is available.
     *
     * @param walletId wallet whose observed amount is updated
     * @param chainSats absolute confirmed balance in satoshis
     * @return accepted or retained observed amount, or {@code -1} for a negative input
     */
    public long applyObserved(UUID walletId, long chainSats) {
        if (chainSats < 0L) {
            return -1L;
        }
        return applyObserved(walletId, ChainProbeResult.confirmedUtxoSet(chainSats, 0, "legacy-absolute"));
    }

    /**
     * Writes an observed balance only when probe quality and previous observation permit replacement.
     * Row locking and the quality decision occur inside one transaction; watch-only writes also zero
     * spendable ledger buckets and publish dashboard state after commit.
     *
     * @param walletId wallet whose observed balance is being considered
     * @param probe chain amount, quality, authority flag, and source metadata
     * @return accepted satoshi balance, previous balance when deferred, or {@code -1} for invalid/unavailable input
     * @throws IllegalArgumentException if the wallet does not exist during transactional evaluation
     */
    public long applyObserved(UUID walletId, ChainProbeResult probe) {
        if (probe == null || probe.quality() == ProbeQuality.UNKNOWN) {
            log.info(
                    "[KFE Onchain Balance] applyObserved deferred walletId={} quality=UNKNOWN source={}",
                    walletId,
                    probe != null ? probe.source() : "null");
            return -1L;
        }
        if (probe.sats() < 0L) {
            return -1L;
        }
        Long result = transactionTemplate.execute(status -> {
            KfeWalletEntity wallet = walletRepository.findById(walletId)
                    .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
            if (!CHAIN_SYNC_KINDS.contains(wallet.getKind())) {
                return 0L;
            }
            KfeBalanceEntity balance = balanceService.requireForUpdate(walletId, ASSET_BTC);
            long previous = balance.getObservedSats();
            ProbeQuality previousQuality = parseProbeQuality(balance.getObservedProbeQuality());
            LocalDateTime previousProbeAt = balance.getObservedProbeAt();
            Decision decision = decideWrite(
                    wallet.getKind(),
                    previous,
                    previousQuality,
                    previousProbeAt,
                    probe,
                    optimisticLiveTtlSeconds);
            if (!decision.write()) {
                log.info(
                        "[KFE Onchain Balance] applyObserved deferred walletId={} kind={} previous={} next={} quality={} prevQuality={} source={} reason={}",
                        walletId,
                        wallet.getKind(),
                        previous,
                        probe.sats(),
                        probe.quality(),
                        previousQuality,
                        probe.source(),
                        decision.reason());
                recordProbeMetric(probe.quality(), "deferred", wallet.getKind());
                recordDeferMetrics(probe.quality(), decision.reason());
                return previous;
            }
            balanceService.setObserved(
                    walletId,
                    ASSET_BTC,
                    probe.sats(),
                    probe.quality().name(),
                    probe.source());
            if (wallet.getKind() == KfeWalletKind.WATCH_ONLY) {
                balanceService.zeroSpendableBucketsIfNeeded(walletId, ASSET_BTC);
            }
            dashboardPublisher.publishAfterCommit(wallet.getUserId());
            recordProbeMetric(probe.quality(), "written", wallet.getKind());
            log.info(
                    "[KFE Onchain Balance] applyObserved walletId={} kind={} previous={} chainSats={} quality={} outpoints={} source={}",
                    walletId,
                    wallet.getKind(),
                    previous,
                    probe.sats(),
                    probe.quality(),
                    probe.outpointCount(),
                    probe.source());
            return probe.sats();
        });
        return result == null ? -1L : result;
    }

    /** Records one accepted/deferred probe result when the optional metrics bean is present. */
    private void recordProbeMetric(ProbeQuality quality, String result, KfeWalletKind kind) {
        KfeBalanceMetrics metrics = balanceMetrics.getIfAvailable();
        if (metrics != null) {
            metrics.recordProbe(quality, result, kind);
        }
    }

    /** Records specific optimistic and monotonic deferral metrics for recognized reason categories. */
    private void recordDeferMetrics(ProbeQuality quality, String reason) {
        KfeBalanceMetrics metrics = balanceMetrics.getIfAvailable();
        if (metrics == null || reason == null) {
            return;
        }
        if (quality == ProbeQuality.OPTIMISTIC_DELTA) {
            metrics.recordOptimisticDeferred(reason);
        }
        if (reason.startsWith("optimistic-will-not-clobber")
                || reason.startsWith("confirmed-utxo-set-will-not-clobber")
                || reason.contains("monotonic")) {
            metrics.recordProbeMonotonicDefer(reason);
        }
    }

    /**
     * Policy for whether a probe may replace {@code previousObserved}.
     * Package-visible for unit tests. Prefer the overload with previous quality/time.
     *
     * @param kind wallet custody kind
     * @param previousObserved previous persisted observed amount
     * @param probe candidate observation
     * @return write/defer decision under default freshness protection
     */
    static Decision decideWrite(KfeWalletKind kind, long previousObserved, ChainProbeResult probe) {
        return decideWrite(kind, previousObserved, null, null, probe, 120L);
    }

    /**
     * Full policy including monotonic protection of fresh LIVE probes against OPTIMISTIC clobber.
     * Custodial wallets accept absolute observations; watch-only wallets require an authoritative live
     * mempool-aware observation, may seed an empty value from confirmed UTXOs, and constrain optimistic deltas.
     *
     * @param kind wallet custody kind
     * @param previousObserved previous persisted observed amount
     * @param previousQuality quality of that observation, if known
     * @param previousProbeAt UTC timestamp of the previous observation
     * @param probe candidate observation and source metadata
     * @param optimisticLiveTtlSeconds age window protecting a fresh authoritative live result
     * @return explicit write decision and stable reason code
     */
    static Decision decideWrite(
            KfeWalletKind kind,
            long previousObserved,
            ProbeQuality previousQuality,
            LocalDateTime previousProbeAt,
            ChainProbeResult probe,
            long optimisticLiveTtlSeconds) {
        if (probe == null || probe.quality() == ProbeQuality.UNKNOWN) {
            return Decision.defer("unknown-quality");
        }
        long next = probe.sats();
        if (kind != KfeWalletKind.WATCH_ONLY) {
            // Custodial: absolute chain mirror is always accepted when we have a probe.
            return Decision.write("custodial-absolute");
        }

        // Monotonic: OPTIMISTIC must not overwrite a fresh LIVE_MEMPOOL_AWARE collect.
        if (probe.quality() == ProbeQuality.OPTIMISTIC_DELTA
                && previousQuality == ProbeQuality.LIVE_MEMPOOL_AWARE
                && previousProbeAt != null
                && optimisticLiveTtlSeconds > 0L) {
            long ageSec = Duration.between(previousProbeAt, LocalDateTime.now(java.time.ZoneOffset.UTC)).getSeconds();
            if (ageSec >= 0L && ageSec < optimisticLiveTtlSeconds) {
                return Decision.defer("optimistic-will-not-clobber-fresh-live");
            }
        }

        return switch (probe.quality()) {
            case LIVE_MEMPOOL_AWARE -> {
                if (probe.authoritative()) {
                    yield Decision.write("live-mempool-aware");
                }
                yield Decision.defer("live-not-authoritative");
            }
            case CONFIRMED_UTXO_SET -> {
                // Mempool-blind: only seed empty cold wallets (import). Never reinflate after live/optimistic.
                if (previousObserved <= 0L) {
                    yield Decision.write("confirmed-seed-empty");
                }
                yield Decision.defer("confirmed-utxo-set-will-not-clobber-live");
            }
            case OPTIMISTIC_DELTA -> {
                // Never collapse to zero via optimistic path (incomplete spend amounts).
                if (next == 0L && previousObserved > 0L) {
                    yield Decision.defer("optimistic-zero-refused");
                }
                if (next == previousObserved) {
                    yield Decision.defer("optimistic-unchanged");
                }
                // Refuse implausible wipe (e.g. multi-output funding debited as whole inbound).
                if (previousObserved > 0L
                        && next < previousObserved
                        && next * 10L < previousObserved) {
                    yield Decision.defer("optimistic-drop-too-large");
                }
                yield Decision.write("optimistic-delta");
            }
            case UNKNOWN -> Decision.defer("unknown");
        };
    }

    /**
     * Parses a persisted quality label case-insensitively, returning null for absent/unknown values.
     *
     * @param raw stored quality label
     * @return matching quality enum or null when unavailable/unrecognized
     */
    static ProbeQuality parseProbeQuality(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ProbeQuality.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * Selects descriptor/address scan behavior according to wallet kind and configured range.
     * Watch-only uses a confirmed descriptor probe with minimum range 50; custodial uses the
     * greater of address and descriptor totals.
     *
     * <p>Incomplete probes must <strong>throw</strong>, never degrade to zero: custodial writes are
     * accepted unconditionally ({@code custodial-absolute}), so a swallowed "scan busy" error would
     * persist {@code observed_sats=0} and raise false custodial drift. The descriptor path is
     * tolerated only when a complete address probe already covered the wallet.
     *
     * @param client available Bitcoin Core client
     * @param wallet wallet metadata and kind
     * @return best confirmed chain balance available from the selected probe paths
     * @throws RuntimeException when no complete observation could be obtained
     */
    long probeChainBalanceSats(BlockchainClient client, KfeWalletEntity wallet) {
        if (wallet.getKind() == KfeWalletKind.WATCH_ONLY) {
            // Confirmed-only descriptor total — cold live path must prefer mempool-aware collect.
            // Do not cap range below cold observation default (aligned via shared resolver + config).
            int descRange = Math.max(descriptorRange, 50);
            String receive = descriptorResolver.resolveReceiveDescriptor(wallet);
            if (receive == null) {
                return probeAddressBalance(client, wallet.getId());
            }
            return probeDescriptorBalance(client, wallet, descRange);
        }
        List<String> addresses = walletAddresses(wallet.getId());
        long fromAddresses = probeAddressBalance(client, addresses);
        long fromDescriptor = 0L;
        try {
            fromDescriptor = probeDescriptorBalance(client, wallet, descriptorRange);
        } catch (RuntimeException exception) {
            if (addresses.isEmpty()) {
                // Descriptor is the only source of truth: an incomplete scan is not "zero".
                throw exception;
            }
            log.warn(
                    "[KFE Onchain Balance] descriptor probe failed walletId={} — using complete address probe: {}",
                    wallet.getId(),
                    exception.getMessage());
        }
        return Math.max(fromDescriptor, fromAddresses);
    }

    /**
     * Queries confirmed funds for receive and derived change descriptors with overflow-safe addition.
     *
     * @param client Bitcoin Core RPC client
     * @param wallet wallet whose receive descriptor is resolved
     * @param range requested descriptor lookahead range
     * @return combined confirmed receive/change balance, or zero when no receive descriptor exists
     */
    private long probeDescriptorBalance(BlockchainClient client, KfeWalletEntity wallet, int range) {
        String receive = descriptorResolver.resolveReceiveDescriptor(wallet);
        if (receive == null) {
            return 0L;
        }
        int safeRange = Math.max(1, range);
        long total = client.getConfirmedBalanceForDescriptor(receive, safeRange);
        String change = KfeWalletDescriptorResolver.toChangeDescriptor(receive);
        if (change != null) {
            total = Math.addExact(total, client.getConfirmedBalanceForDescriptor(change, safeRange));
        }
        return total;
    }

    /**
     * Loads the distinct, trimmed, nonblank addresses registered for a wallet (newest first).
     *
     * @param walletId wallet whose addresses are loaded
     * @return immutable address list, possibly empty
     */
    private List<String> walletAddresses(UUID walletId) {
        return addressRepository.findByWalletIdOrderByCreatedAtDesc(walletId).stream()
                .map(KfeWalletAddressEntity::getAddress)
                .filter(address -> address != null && !address.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    /** Address-only probe used by watch-only wallets without a descriptor. */
    private long probeAddressBalance(BlockchainClient client, UUID walletId) {
        return probeAddressBalance(client, walletAddresses(walletId));
    }

    /**
     * Queries addresses through wallet-aware unspent lookup and per-address UTXO-set scans,
     * returning the larger total.
     *
     * <p>Every per-address scan must complete. Bitcoin Core allows a single {@code scantxoutset}
     * at a time, so "scan busy" is an expected transient failure; summing it as 0 sats used to
     * write {@code observed_sats=0} for funded custodial wallets. Failures therefore propagate and
     * {@link #syncWallet(UUID)} keeps the previous observation.
     *
     * @param client Bitcoin Core RPC client
     * @param addresses distinct wallet addresses
     * @return best confirmed balance reported by the available address probe methods
     * @throws RuntimeException when any address scan fails or is incomplete
     */
    private long probeAddressBalance(BlockchainClient client, List<String> addresses) {
        if (addresses.isEmpty()) {
            return 0L;
        }
        // listunspent only sees Core-wallet-imported scripts; scantxoutset(addr) always works.
        long fromListUnspent = client.getUnspentBalanceForAddresses(addresses);
        long fromScan = 0L;
        for (String address : addresses) {
            fromScan = Math.addExact(fromScan, client.getConfirmedBalanceForAddress(address));
        }
        return Math.max(fromListUnspent, fromScan);
    }

    /**
     * Immutable result of evaluating whether one probe may replace the current observed balance.
     *
     * @param write whether the candidate should replace the current observed value
     * @param reason stable policy reason code used by logs and metrics
     */
    record Decision(boolean write, String reason) {
        /**
         * Creates a decision that accepts the candidate balance.
         *
         * @param reason stable write reason code
         * @return accepted decision
         */
        static Decision write(String reason) {
            return new Decision(true, reason);
        }

        /**
         * Creates a decision that retains the current balance and reports a reason code.
         *
         * @param reason stable deferral reason code
         * @return deferred decision
         */
        static Decision defer(String reason) {
            return new Decision(false, reason);
        }
    }
}
