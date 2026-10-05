package com.kerosene.kfe.ledger.adapters.in.reconciliation;

import com.kerosene.kfe.ledger.adapters.out.observability.KfeBalanceMetrics;
import com.kerosene.kfe.wallet.adapters.in.observation.KfeColdWalletObservationService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceEntity;
import com.kerosene.kfe.adapters.out.persistence.model.paymentexecution.KfeTransactionStatus;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeTransactionRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Periodically compares custodial ledger values with observations, refreshes watch-only wallets,
 * and reports suspicious locked balances. The job is observational and may request soft probes;
 * it never releases locks or mutates financial balances automatically.
 */
@Service
@ConditionalOnProperty(name = "kfe.balance-reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class KfeBalanceReconciliationJob {

    /** Logger for drift, stale observation, lock, and pass-failure diagnostics. */
    private static final Logger log = LoggerFactory.getLogger(KfeBalanceReconciliationJob.class);

    /** Wallet metadata source for active custodial and watch-only wallets. */
    private final KfeWalletRepository walletRepository;
    /** Ledger balance source for available, locked, and observed satoshi totals. */
    private final KfeBalanceRepository balanceRepository;
    /** Transaction source used to identify open operations holding locks. */
    private final KfeTransactionRepository transactionRepository;
    /** Optional service used for best-effort custodial wallet resynchronization. */
    private final ObjectProvider<KfeOnchainBalanceSyncService> balanceSyncService;
    /** Optional service used to refresh watch-only wallet observations. */
    private final ObjectProvider<KfeColdWalletObservationService> coldObservationService;
    /** Metrics sink for drift magnitudes and stuck lock incidents. */
    private final KfeBalanceMetrics metrics;
    /** Minimum absolute ledger/observed difference that triggers a soft custodial re-probe. */
    private final long driftThresholdSats;
    /** Age beyond which an open transaction retaining locks is reported. */
    private final int lockedStuckMinutes;
    /** Maximum number of watch-only wallets refreshed in one scheduled pass. */
    private final int coldBatchSize;

    /**
     * Creates the reconciliation job and clamps negative/zero thresholds to safe minimums.
     *
     * @param walletRepository wallet metadata store
     * @param balanceRepository balance store
     * @param transactionRepository transaction status store
     * @param balanceSyncService optional custodial soft-sync provider
     * @param coldObservationService optional watch-only observation provider
     * @param metrics reconciliation metrics sink
     * @param driftThresholdSats drift threshold in satoshis
     * @param lockedStuckMinutes age threshold for suspicious locked funds
     * @param coldBatchSize maximum watch-only wallets examined each run
     */
    public KfeBalanceReconciliationJob(
            KfeWalletRepository walletRepository,
            KfeBalanceRepository balanceRepository,
            KfeTransactionRepository transactionRepository,
            ObjectProvider<KfeOnchainBalanceSyncService> balanceSyncService,
            ObjectProvider<KfeColdWalletObservationService> coldObservationService,
            KfeBalanceMetrics metrics,
            @Value("${kfe.balance-reconciliation.drift-threshold-sats:1000}") long driftThresholdSats,
            @Value("${kfe.balance-reconciliation.locked-stuck-minutes:30}") int lockedStuckMinutes,
            @Value("${kfe.balance-reconciliation.cold-batch-size:20}") int coldBatchSize) {
        this.walletRepository = walletRepository;
        this.balanceRepository = balanceRepository;
        this.transactionRepository = transactionRepository;
        this.balanceSyncService = balanceSyncService;
        this.coldObservationService = coldObservationService;
        this.metrics = metrics;
        this.driftThresholdSats = Math.max(0L, driftThresholdSats);
        this.lockedStuckMinutes = Math.max(1, lockedStuckMinutes);
        this.coldBatchSize = Math.max(1, coldBatchSize);
    }

    /**
     * Runs the three independent reconciliation passes, allowing later passes to proceed if one fails.
     * Each failure is logged at the pass boundary and does not cancel future scheduled invocations.
     */
    @Scheduled(
            fixedDelayString = "${kfe.balance-reconciliation.fixed-delay-ms:180000}",
            initialDelayString = "${kfe.balance-reconciliation.initial-delay-ms:90000}")
    public void reconcile() {
        try {
            checkCustodialDrift();
        } catch (RuntimeException exception) {
            log.warn("[KFE Balance Recon] custodial drift pass failed: {}", exception.getMessage());
        }
        try {
            refreshStaleColdWallets();
        } catch (RuntimeException exception) {
            log.warn("[KFE Balance Recon] cold refresh pass failed: {}", exception.getMessage());
        }
        try {
            checkLockedStuck();
        } catch (RuntimeException exception) {
            log.warn("[KFE Balance Recon] locked-stuck pass failed: {}", exception.getMessage());
        }
    }

    /**
     * Measures absolute differences between custodial available-plus-locked ledger value and observations.
     * Overflows are recorded as maximal drift; values beyond the configured threshold trigger a best-effort
     * balance sync without changing or releasing ledger funds here.
     */
    @Transactional(readOnly = true)
    protected void checkCustodialDrift() {
        List<KfeWalletEntity> wallets = walletRepository.findByKindInAndStatus(
                List.of(KfeWalletKind.CUSTODIAL_ONCHAIN), KfeWalletStatus.ACTIVE);
        if (wallets.isEmpty()) {
            return;
        }
        Map<UUID, KfeBalanceEntity> balances = indexBalances(
                balanceRepository.findByWalletIds(wallets.stream().map(KfeWalletEntity::getId).toList()));
        KfeOnchainBalanceSyncService sync = balanceSyncService.getIfAvailable();
        for (KfeWalletEntity wallet : wallets) {
            KfeBalanceEntity balance = balances.get(wallet.getId());
            if (balance == null) {
                continue;
            }
            long ledger;
            try {
                ledger = Math.addExact(balance.getAvailableSats(), balance.getLockedSats());
            } catch (ArithmeticException overflow) {
                log.error("[KFE Balance Recon] ledger total overflow walletId={}", wallet.getId());
                metrics.recordDrift(KfeWalletKind.CUSTODIAL_ONCHAIN, Long.MAX_VALUE);
                continue;
            }
            long observed = balance.getObservedSats();
            long absDrift = ledger >= observed
                    ? Math.subtractExact(ledger, observed)
                    : Math.subtractExact(observed, ledger);
            metrics.recordDrift(KfeWalletKind.CUSTODIAL_ONCHAIN, absDrift);
            if (absDrift > driftThresholdSats) {
                log.warn(
                        "[KFE Balance Recon] custodial drift walletId={} available={} locked={} observed={} driftSats={}",
                        wallet.getId(),
                        balance.getAvailableSats(),
                        balance.getLockedSats(),
                        observed,
                        absDrift);
                // Soft re-probe; non-fatal.
                if (sync != null) {
                    try {
                        sync.syncWallet(wallet.getId());
                    } catch (RuntimeException exception) {
                        log.debug(
                                "[KFE Balance Recon] resync failed walletId={}: {}",
                                wallet.getId(),
                                exception.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Refreshes a bounded prefix of active WATCH_ONLY wallets when an observation adapter is available.
     * Individual wallet failures are isolated so one bad address does not stop the batch.
     */
    protected void refreshStaleColdWallets() {
        KfeColdWalletObservationService cold = coldObservationService.getIfAvailable();
        if (cold == null) {
            return;
        }
        List<KfeWalletEntity> wallets = walletRepository.findByKindInAndStatus(
                List.of(KfeWalletKind.WATCH_ONLY), KfeWalletStatus.ACTIVE);
        int limit = Math.min(coldBatchSize, wallets.size());
        for (int i = 0; i < limit; i++) {
            try {
                cold.observeWallet(wallets.get(i).getId());
            } catch (RuntimeException exception) {
                log.debug(
                        "[KFE Balance Recon] cold observe failed walletId={}: {}",
                        wallets.get(i).getId(),
                        exception.getMessage());
            }
        }
    }

    /**
     * Reports active internal/custodial wallets whose locks outlive open transactions or have no open transaction.
     * This pass records metrics and logs only; it deliberately leaves financial state untouched.
     */
    @Transactional(readOnly = true)
    protected void checkLockedStuck() {
        LocalDateTime cutoff = LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(lockedStuckMinutes);
        List<KfeWalletEntity> wallets = walletRepository.findByKindInAndStatus(
                List.of(KfeWalletKind.INTERNAL, KfeWalletKind.CUSTODIAL_ONCHAIN),
                KfeWalletStatus.ACTIVE);
        Map<UUID, KfeBalanceEntity> balances = indexBalances(
                balanceRepository.findByWalletIds(wallets.stream().map(KfeWalletEntity::getId).toList()));
        for (KfeWalletEntity wallet : wallets) {
            KfeBalanceEntity balance = balances.get(wallet.getId());
            if (balance == null || balance.getLockedSats() <= 0L) {
                continue;
            }
            // Open outbound still holding locks for too long.
            var open = transactionRepository.findByWalletIdAndStatusIn(
                    wallet.getId(),
                    List.of(
                            KfeTransactionStatus.EXECUTING,
                            KfeTransactionStatus.VALIDATING,
                            KfeTransactionStatus.REQUIRES_RECONCILIATION));
            boolean stuck = open.stream().anyMatch(tx -> {
                LocalDateTime created = tx.getCreatedAt();
                return created != null && created.isBefore(cutoff);
            });
            if (stuck || open.isEmpty()) {
                // empty open + locked > 0 is also suspicious (orphan lock)
                log.warn(
                        "[KFE Balance Recon] locked funds walletId={} lockedSats={} openTxs={} stuckOrOrphan={}",
                        wallet.getId(),
                        balance.getLockedSats(),
                        open.size(),
                        stuck || open.isEmpty());
                metrics.recordLockedStuck();
            }
        }
    }

    /**
     * Indexes balance rows by wallet ID, skipping rows whose embedded identity is missing.
     * Later rows for a duplicate wallet ID replace earlier entries.
     *
     * @param balances queried balance rows
     * @return mutable lookup map keyed by wallet identifier
     */
    private static Map<UUID, KfeBalanceEntity> indexBalances(List<KfeBalanceEntity> balances) {
        Map<UUID, KfeBalanceEntity> map = new HashMap<>();
        for (KfeBalanceEntity balance : balances) {
            if (balance.getId() != null && balance.getId().getWalletId() != null) {
                map.put(balance.getId().getWalletId(), balance);
            }
        }
        return map;
    }
}
