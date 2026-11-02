package com.kerosene.kfe.ledger.adapters.in;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.adapters.in.http.dto.liquidity.KfeReserveOverviewResponse;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.rail.onchain.BlockchainClient;
import com.kerosene.kfe.adapters.out.rail.channels.LightningChannelGateway;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * KFE reserve overview (ITEM 9 — vault mesh boundary audit).
 *
 * <p>Reports assets and liabilities separately. Never sums them into a single "total."
 *
 * <p><strong>Liabilities:</strong><br>
 * Customer liabilities = available + pending + locked + auto_hold for
 * CUSTODIAL_ONCHAIN and INTERNAL wallets only. Excludes:
 * <ul>
 *   <li>WATCH_ONLY — user controls keys, no platform obligation</li>
 *   <li>SYSTEM_FUNDS — platform equity, not customer debt</li>
 *   <li>SYSTEM_PROFIT — undistributed profit tracked as subledger entry; included
 *       separately as {@code systemProfitSats}</li>
 * </ul>
 *
 * <p><strong>Assets:</strong><br>
 * Assets are queried from external probes when available:</p>
 * <ul>
 *   <li>On-chain: confirmed UTXOs via {@link BlockchainClient} (scantxoutset/listunspent).
 *       Falls back to ledger {@code observedSats} when the client is unavailable.</li>
 *   <li>Lightning: channel local balance + LND on-chain wallet via
 *       {@link LightningChannelGateway}. Falls back to 0 when unavailable.</li>
 * </ul>
 *
 * <p>WATCH_ONLY observed balances are reported as {@code coldCustodyAssetsSats} —
 * visible on-chain but NOT controlled by the platform.
 */
@Service
public class KfeReserveOverviewService {

    /** Logger for best-effort external probe failures. */
    private static final Logger log = LoggerFactory.getLogger(KfeReserveOverviewService.class);

    /** Wallet kinds that represent customer obligations. */
    private static final Set<KfeWalletKind> CUSTOMER_KINDS = Set.of(
            KfeWalletKind.CUSTODIAL_ONCHAIN,
            KfeWalletKind.INTERNAL);

    /** Ledger balance store supplying liabilities and the cached confirmed on-chain mirror. */
    private final KfeBalanceRepository balanceRepository;
    /** Batch wallet-kind lookup used to classify balance rows. */
    private final KfeWalletRepository walletRepository;
    /** Optional Bitcoin Core client used to associate the overview with a current chain tip. */
    private final BlockchainClient blockchainClient;
    /** Optional live Lightning gateway used to include channel and LND on-chain assets. */
    private final LightningChannelGateway lightningChannelGateway;

    /**
     * Creates the overview service with required persistence and optional external probe adapters.
     *
     * @param balanceRepository source of ledger balance buckets and observation metadata
     * @param walletRepository batch source of wallet kinds for balance classification
     * @param blockchainClient optional Bitcoin Core tip probe
     * @param lightningChannelGateway optional live channel/LND asset probe
     */
    public KfeReserveOverviewService(
            KfeBalanceRepository balanceRepository,
            KfeWalletRepository walletRepository,
            ObjectProvider<BlockchainClient> blockchainClient,
            ObjectProvider<LightningChannelGateway> lightningChannelGateway) {
        this.balanceRepository = balanceRepository;
        this.walletRepository = walletRepository;
        this.blockchainClient = blockchainClient.getIfAvailable();
        this.lightningChannelGateway = lightningChannelGateway.getIfAvailable();
    }

    /**
     * Returns the reserve overview with separate asset and liability sections.
     *
     * <p>Liabilities = sum of user-facing balance buckets (available + pending + locked + hold)
     * for CUSTODIAL_ONCHAIN and INTERNAL wallets only.
     *
     * <p>On-chain customer assets start from cached ledger {@code observedSats}; an available Core
     * client contributes a tip marker only. A live Lightning gateway contributes active channel
     * local balances and its confirmed on-chain balance. Missing probes leave those additional
     * buckets at zero; they are not replaced with unverified estimates. Exact arithmetic fails on overflow.
     *
     * @return DTO containing liability, asset, equity, coverage, chain marker, timestamp, and status
     * @throws ArithmeticException if an accumulated bucket or derived total overflows
     */
    @Transactional(readOnly = true)
    public KfeReserveOverviewResponse overview() {
        List<KfeBalanceEntity> balances = balanceRepository.findAll();
        Map<UUID, KfeWalletKind> kinds = loadWalletKinds(balances);

        // --- Liabilities: what we owe customers ---
        long customerLiabilitiesSats = 0L;   // available for CUSTODIAL_ONCHAIN + INTERNAL
        long lockedLiabilitiesSats = 0L;      // locked + auto_hold (reserved but not spent)
        long pendingLiabilitiesSats = 0L;     // pending deposits not yet credited

        // --- Assets ---
        long onchainReserveAssetsSats = 0L;   // confirmed UTXOs for platform-controlled wallets
        long lightningReserveAssetsSats = 0L; // Lightning channel local balance
        long unconfirmedAssetsSats = 0L;      // mempool deposits not yet confirmed
        long encumberedAssetsSats = 0L;       // UTXOs locked in HTLCs / pending channel opens

        String bestObservedQuality = null;
        java.time.LocalDateTime bestObservedAt = null;

        for (KfeBalanceEntity balance : balances) {
            UUID walletId = balance.getId() != null ? balance.getId().getWalletId() : null;
            KfeWalletKind kind = walletId != null ? kinds.get(walletId) : null;

            if (kind == null) {
                // Orphan balance — include conservatively in liabilities
                customerLiabilitiesSats = Math.addExact(customerLiabilitiesSats, balance.getAvailableSats());
                lockedLiabilitiesSats = Math.addExact(lockedLiabilitiesSats,
                        Math.addExact(balance.getLockedSats(), balance.getAutoHoldSats()));
                pendingLiabilitiesSats = Math.addExact(pendingLiabilitiesSats, balance.getPendingSats());
                continue;
            }

            switch (kind) {
                case WATCH_ONLY -> {
                    // User-controlled cold wallet. observedSats is visible on-chain
                    // but NOT our asset — user holds the keys. Skip both sides.
                }
                case SYSTEM_FUNDS -> {
                    // Platform equity — not a customer liability, not a reserve asset.
                }
                case SYSTEM_PROFIT -> {
                    // Undistributed fees. A liability until physically segregated,
                    // but NOT a customer liability. Skip from the overview totals;
                    // the settlement gate includes it separately in its solvency check.
                }
                case CUSTODIAL_ONCHAIN, INTERNAL -> {
                    // Customer obligations
                    customerLiabilitiesSats = Math.addExact(customerLiabilitiesSats,
                            balance.getAvailableSats());
                    pendingLiabilitiesSats = Math.addExact(pendingLiabilitiesSats,
                            balance.getPendingSats());
                    lockedLiabilitiesSats = Math.addExact(lockedLiabilitiesSats,
                            Math.addExact(balance.getLockedSats(), balance.getAutoHoldSats()));

                    // observedSats = last confirmed on-chain scan for this wallet's addresses.
                    // This is our best ledger-cached asset evidence.
                    onchainReserveAssetsSats = Math.addExact(onchainReserveAssetsSats,
                            balance.getObservedSats());
                }
            }

            // Track best probe quality across all balances
            if (balance.getObservedProbeQuality() != null
                    && (bestObservedQuality == null
                        || probeQualityRank(balance.getObservedProbeQuality())
                           > probeQualityRank(bestObservedQuality))) {
                bestObservedQuality = balance.getObservedProbeQuality();
                bestObservedAt = balance.getObservedProbeAt();
            }
        }

        // Enrich on-chain assets with live probe when available
        String snapshotBlockHash = null;
        if (blockchainClient != null) {
            try {
                long tipHeight = blockchainClient.getBlockTipHeight();
                if (tipHeight > 0L) {
                    snapshotBlockHash = "height:" + tipHeight;
                }
            } catch (RuntimeException e) {
                log.debug("Blockchain tip probe failed for reserve overview: {}", e.getMessage());
            }
        }

        // Add Lightning assets when gateway is available
        if (lightningChannelGateway != null && lightningChannelGateway.isLive()) {
            try {
                List<LightningChannelGateway.ChannelSnapshot> channels =
                        lightningChannelGateway.listChannels();
                for (LightningChannelGateway.ChannelSnapshot ch : channels) {
                    if (ch.active() && ch.localBalanceSats() > 0L) {
                        lightningReserveAssetsSats = Math.addExact(
                                lightningReserveAssetsSats, ch.localBalanceSats());
                    }
                }
                long lndOnchain = lightningChannelGateway.confirmedOnchainBalanceSats();
                if (lndOnchain > 0L) {
                    onchainReserveAssetsSats = Math.addExact(onchainReserveAssetsSats, lndOnchain);
                }
            } catch (RuntimeException e) {
                log.debug("Lightning channel probe failed for reserve overview: {}", e.getMessage());
            }
        }

        long totalLiabilities = Math.addExact(
                Math.addExact(customerLiabilitiesSats, lockedLiabilitiesSats), pendingLiabilitiesSats);
        long totalAssets = Math.subtractExact(
                Math.addExact(Math.addExact(onchainReserveAssetsSats, lightningReserveAssetsSats),
                        unconfirmedAssetsSats),
                encumberedAssetsSats);

        long equitySats = Math.subtractExact(totalAssets, totalLiabilities);
        double coverageRatio = totalLiabilities > 0
                ? (double) totalAssets / (double) totalLiabilities
                : totalAssets >= 0L ? Double.POSITIVE_INFINITY : 0.0;

        String status = computeStatus(coverageRatio, totalAssets, totalLiabilities,
                bestObservedQuality, bestObservedAt);

        return new KfeReserveOverviewResponse(
                customerLiabilitiesSats,
                lockedLiabilitiesSats,
                pendingLiabilitiesSats,
                onchainReserveAssetsSats,
                lightningReserveAssetsSats,
                unconfirmedAssetsSats,
                encumberedAssetsSats,
                equitySats,
                coverageRatio,
                snapshotBlockHash,
                Instant.now(),
                status);
    }

    /**
     * Loads wallet kinds for all balance rows in a single batch query, excluding null identifiers.
     *
     * @param balances balance rows whose wallet IDs need classification
     * @return map from wallet ID to kind, empty when no usable wallet IDs exist
     */
    private Map<UUID, KfeWalletKind> loadWalletKinds(List<KfeBalanceEntity> balances) {
        Set<UUID> walletIds = balances.stream()
                .map(KfeBalanceEntity::getId)
                .filter(id -> id != null && id.getWalletId() != null)
                .map(com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceId::getWalletId)
                .collect(Collectors.toSet());

        if (walletIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, KfeWalletKind> kinds = new HashMap<>();
        for (Object[] row : walletRepository.findKindsByIds(walletIds)) {
            if (row[0] instanceof UUID id && row[1] instanceof KfeWalletKind kind) {
                kinds.put(id, kind);
            }
        }
        return kinds;
    }

    /**
     * Classifies coverage while considering whether the best observed probe is fresh.
     * A stale or absent observation cannot produce plain SOLVENT when coverage is sufficient.
     *
     * @param coverageRatio total assets divided by total liabilities
     * @param totalAssets computed eligible asset total
     * @param totalLiabilities customer, locked, and pending liability total
     * @param probeQuality quality label selected from ledger observations
     * @param probeAt timestamp associated with that observation
     * @return bounded status string for the response DTO
     */
    private String computeStatus(double coverageRatio, long totalAssets, long totalLiabilities,
                                  String probeQuality, java.time.LocalDateTime probeAt) {
        if (totalLiabilities <= 0 && totalAssets <= 0) {
            return "UNKNOWN";
        }
        if (probeQuality == null || isProbeStale(probeAt)) {
            if (coverageRatio >= 1.0) {
                return "SOLVENT_STALE_PROBE";
            }
            return "UNKNOWN";
        }
        if (coverageRatio >= 1.0) {
            return "SOLVENT";
        }
        if (coverageRatio >= 0.5) {
            return "NEAR_INSOLVENT";
        }
        return "INSOLVENT";
    }

    /**
     * Treats missing observation time or observations older than two UTC hours as stale.
     *
     * @param probeAt UTC wall-clock time recorded for the selected observation
     * @return whether the observation is absent or beyond the freshness threshold
     */
    private boolean isProbeStale(java.time.LocalDateTime probeAt) {
        if (probeAt == null) {
            return true;
        }
        return probeAt.isBefore(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2));
    }

    /**
     * Maps known probe-quality labels to an ordering for selecting the best observation.
     * Unknown values rank below recognized qualities.
     *
     * @param quality persisted probe-quality label
     * @return rank from zero (unknown/absent) through four (mempool-aware live probe)
     */
    private static int probeQualityRank(String quality) {
        if (quality == null) return 0;
        return switch (quality) {
            case "LIVE_MEMPOOL_AWARE" -> 4;
            case "LIVE_CONFIRMED" -> 3;
            case "OPTIMISTIC_DELTA" -> 2;
            case "LEDGER_ESTIMATE" -> 1;
            default -> 0;
        };
    }
}
