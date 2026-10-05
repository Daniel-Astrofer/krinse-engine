package com.kerosene.kfe.ledger.adapters.out.persistence.balance;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.messaging.adapters.out.websocket.BalanceEventPublisher;
import com.kerosene.kfe.messaging.adapters.out.websocket.BalanceUpdateEvent;

import java.time.ZoneOffset;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Persists KFE balance transitions under row locks, refreshes integrity hashes, and emits UI snapshots.
 * All arithmetic and transitions delegate to {@link KfeBalanceEntity}; this adapter also preserves
 * observed-chain state separately from spendable buckets and isolates event publication failures.
 */
@Service
public class KfeBalanceService {

    /** Logger for best-effort event publication and defensive watch-only bucket cleanup. */
    private static final Logger log = LoggerFactory.getLogger(KfeBalanceService.class);

    /** Ledger balance persistence adapter. */
    private final KfeBalanceRepository balanceRepository;
    /** Integrity hash service used after each persisted mutation. */
    private final KfeHashService hashService;
    /** Wallet metadata source used to build balance events. */
    private final KfeWalletRepository walletRepository;
    /** After-commit publisher for balance snapshots. */
    private final BalanceEventPublisher balanceEventPublisher;

    /**
     * Creates the balance service with persistence, integrity, wallet, and event dependencies.
     *
     * @param balanceRepository store for balance entities and row locks
     * @param hashService canonical balance hash implementation
     * @param walletRepository source of wallet kind/label/user metadata
     * @param balanceEventPublisher publisher that schedules event delivery after commit
     */
    public KfeBalanceService(KfeBalanceRepository balanceRepository,
                             KfeHashService hashService,
                             KfeWalletRepository walletRepository,
                             BalanceEventPublisher balanceEventPublisher) {
        this.balanceRepository = balanceRepository;
        this.hashService = hashService;
        this.walletRepository = walletRepository;
        this.balanceEventPublisher = balanceEventPublisher;
    }

    /**
     * Creates and saves a zeroed asset row with initial integrity hash/signature metadata.
     *
     * @param walletId owning wallet identifier
     * @param asset asset code, defaulted to BTC only when null
     * @return newly persisted empty balance entity
     * @throws IllegalArgumentException if walletId is null
     */
    public KfeBalanceEntity createEmptyBalance(UUID walletId, String asset) {
        if (walletId == null) {
            throw new IllegalArgumentException("walletId is required.");
        }
        String normalizedAsset = asset != null ? asset : "BTC";
        String initialHash = hashService.initialBalanceHash(walletId.toString(), normalizedAsset);
        KfeBalanceEntity balance = KfeBalanceEntity.empty(walletId, normalizedAsset, initialHash);
        balance.setBalanceSignature(hashService.balanceHash(balance));
        return balanceRepository.save(balance);
    }

    /**
     * Retrieves a balance under a database write lock for a caller's surrounding transaction.
     *
     * @param walletId owning wallet identifier
     * @param asset asset code, defaulted to BTC only when null
     * @return locked persisted balance row
     * @throws IllegalArgumentException if walletId is null or the row does not exist
     */
    public KfeBalanceEntity requireForUpdate(UUID walletId, String asset) {
        if (walletId == null) {
            throw new IllegalArgumentException("walletId is required.");
        }
        return balanceRepository.findByWalletIdAndAssetForUpdate(walletId, asset != null ? asset : "BTC")
                .orElseThrow(() -> new IllegalArgumentException("KFE balance not found for wallet " + walletId + "."));
    }

    /**
     * Moves the requested amount from available to locked and persists the signed balance snapshot.
     *
     * @param walletId wallet whose available balance is reserved
     * @param asset asset code
     * @param amountSats positive amount in satoshis
     * @return saved balance after reservation
     */
    public KfeBalanceEntity reserve(UUID walletId, String asset, long amountSats) {
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        balance.reserve(amountSats);
        sign(balance);
        KfeBalanceEntity saved = balanceRepository.save(balance);
        publishBalanceSnapshot(walletId, saved, -amountSats, "reserva", "AVAILABLE");
        return saved;
    }

    /**
     * Consumes locked funds after the reserved payment has settled.
     *
     * @param walletId wallet holding the reservation
     * @param asset asset code
     * @param amountSats reserved debit amount in satoshis
     * @return saved balance after settlement
     */
    public KfeBalanceEntity settleReservedDebit(UUID walletId, String asset, long amountSats) {
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        balance.settleReservedDebit(amountSats);
        sign(balance);
        KfeBalanceEntity saved = balanceRepository.save(balance);
        publishBalanceSnapshot(walletId, saved, 0L, "liquidação de débito", "LOCKED");
        return saved;
    }

    /**
     * Moves unused reserved funds from locked back to available and publishes the resulting snapshot.
     *
     * @param walletId wallet holding the reservation
     * @param asset asset code
     * @param amountSats amount to release in satoshis
     * @return saved balance after release
     */
    public KfeBalanceEntity releaseReserved(UUID walletId, String asset, long amountSats) {
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        balance.releaseReserved(amountSats);
        sign(balance);
        KfeBalanceEntity saved = balanceRepository.save(balance);
        publishBalanceSnapshot(walletId, saved, amountSats, "liberação de reserva", "AVAILABLE");
        return saved;
    }

    /**
     * Credits spendable available balance and publishes its exact increase.
     *
     * @param walletId receiving wallet identifier
     * @param asset asset code
     * @param amountSats positive credit amount in satoshis
     * @return saved balance after credit
     */
    public KfeBalanceEntity creditAvailable(UUID walletId, String asset, long amountSats) {
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        long availableBefore = balance.getAvailableSats();
        balance.creditAvailable(amountSats);
        sign(balance);
        KfeBalanceEntity saved = balanceRepository.save(balance);
        publishBalanceSnapshot(
                walletId, saved, saved.getAvailableSats() - availableBefore, "crédito", "AVAILABLE");
        return saved;
    }

    /**
     * Reverses an available credit after a chain reorganization, recording any uncovered remainder as debt.
     *
     * @param walletId affected wallet identifier
     * @param asset asset code
     * @param amountSats amount of the original credit to reverse
     * @return actual available debit, newly added debt, and resulting total reorg debt
     */
    public ReorgDebitResult reverseAvailableCreditForReorg(
            UUID walletId,
            String asset,
            long amountSats) {
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        long availableBefore = balance.getAvailableSats();
        long debtBefore = balance.getReorgDebtSats();
        balance.reverseAvailableCreditForReorg(amountSats);
        sign(balance);
        KfeBalanceEntity saved = balanceRepository.save(balance);
        long debited = availableBefore - saved.getAvailableSats();
        long debtAdded = saved.getReorgDebtSats() - debtBefore;
        publishBalanceSnapshot(walletId, saved, -debited, "reorg", "AVAILABLE");
        return new ReorgDebitResult(debited, debtAdded, saved.getReorgDebtSats());
    }

    /**
     * Result of reversing a credit when available funds may not cover its entire amount.
     *
     * @param debitedSats amount actually removed from available balance
     * @param debtAddedSats uncovered amount added to reorg debt
     * @param totalDebtSats resulting total reorg debt
     */
    public record ReorgDebitResult(
            long debitedSats,
            long debtAddedSats,
            long totalDebtSats) {
    }

    /**
     * Replaces the observed on-chain balance without attaching probe provenance metadata.
     *
     * @param walletId wallet being observed
     * @param asset asset code
     * @param observedSats absolute observed amount in satoshis
     * @return saved balance after the observation update
     */
    public KfeBalanceEntity setObserved(UUID walletId, String asset, long observedSats) {
        return setObserved(walletId, asset, observedSats, null, null);
    }

    /**
     * Replaces the absolute observed amount and optionally stores probe quality/source metadata.
     *
     * @param walletId wallet being observed
     * @param asset asset code
     * @param observedSats absolute amount reported by the chain probe
     * @param probeQuality optional probe quality label used by monotonic policies
     * @param probeSource optional probe source label retained with the timestamp
     * @return saved balance after the observation update
     */
    public KfeBalanceEntity setObserved(
            UUID walletId,
            String asset,
            long observedSats,
            String probeQuality,
            String probeSource) {
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        long oldObserved = balance.getObservedSats();
        balance.setObservedBalance(observedSats);
        if (probeQuality != null && !probeQuality.isBlank()) {
            balance.setObservedProbeMeta(
                    probeQuality.trim(),
                    java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
                    probeSource != null && !probeSource.isBlank() ? probeSource.trim() : null);
        }
        sign(balance);
        KfeBalanceEntity saved = balanceRepository.save(balance);
        publishBalanceSnapshot(walletId, saved, Math.subtractExact(observedSats, oldObserved), "observado", "OBSERVED");
        return saved;
    }

    /**
     * Increments observed balance for compatibility with legacy callers.
     * Prefer {@link #setObserved} with an absolute chain probe for WATCH_ONLY/CUSTODIAL_ONCHAIN.
     *
     * @param walletId wallet receiving the observation increment
     * @param asset asset code
     * @param amountSats positive increment in satoshis
     * @return saved balance after increment
     * @throws IllegalArgumentException when amountSats is not positive
     * @throws ArithmeticException when the resulting balance overflows
     */
    public KfeBalanceEntity creditObserved(UUID walletId, String asset, long amountSats) {
        if (amountSats <= 0L) {
            throw new IllegalArgumentException("observed credit amount must be positive.");
        }
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        long next = Math.addExact(balance.getObservedSats(), amountSats);
        balance.setObservedBalance(next);
        sign(balance);
        KfeBalanceEntity saved = balanceRepository.save(balance);
        publishBalanceSnapshot(walletId, saved, amountSats, "crédito observado", "OBSERVED");
        return saved;
    }

    /**
     * Ensures a watch-only wallet has no available, pending, locked, or auto-hold spendable balance.
     * If any bucket is nonzero, it clears all four, increments the entity version, re-signs, and saves.
     *
     * @param walletId watch-only wallet identifier
     * @param asset asset code
     * @return unchanged locked row when already zeroed, otherwise saved cleared row
     */
    public KfeBalanceEntity zeroSpendableBucketsIfNeeded(UUID walletId, String asset) {
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        if (balance.getAvailableSats() == 0L
                && balance.getPendingSats() == 0L
                && balance.getLockedSats() == 0L
                && balance.getAutoHoldSats() == 0L) {
            return balance;
        }
        log.warn(
                "Clearing non-zero spendable buckets on cold/watch-only walletId={} available={} pending={} locked={} autoHold={}",
                walletId,
                balance.getAvailableSats(),
                balance.getPendingSats(),
                balance.getLockedSats(),
                balance.getAutoHoldSats());
        balance.setAvailableSats(0L);
        balance.setPendingSats(0L);
        balance.setLockedSats(0L);
        balance.setAutoHoldSats(0L);
        balance.setNonce(Math.addExact(balance.getNonce(), 1L));
        sign(balance);
        return balanceRepository.save(balance);
    }

    /** Recomputes both persisted integrity fields from the entity's current balance state. */
    private void sign(KfeBalanceEntity balance) {
        String hash = hashService.balanceHash(balance);
        balance.setLastHash(hash);
        balance.setBalanceSignature(hash);
    }

    /**
     * Builds and schedules the UI balance projection from current wallet metadata and persisted buckets.
     * Exceptions are logged and contained so a websocket failure does not roll back a balance mutation.
     *
     * @param walletId owner wallet identifier
     * @param balance saved balance entity
     * @param deltaSats signed display delta in satoshis
     * @param context movement label shown to clients
     * @param bucket bucket associated with the transition
     */
    private void publishBalanceSnapshot(
            UUID walletId,
            KfeBalanceEntity balance,
            long deltaSats,
            String context,
            String bucket) {
        try {
            walletRepository.findById(walletId).ifPresent(wallet -> {
                KfeWalletKind kind = wallet.getKind() != null ? wallet.getKind() : KfeWalletKind.INTERNAL;
                long primarySats = primarySatsFor(kind, balance);
                BigDecimal newBalance = BigDecimal.valueOf(primarySats).movePointLeft(8);
                BigDecimal amount = BigDecimal.valueOf(deltaSats).movePointLeft(8);
                BalanceUpdateEvent event = new BalanceUpdateEvent(
                        wallet.getId().toString(),
                        wallet.getLabel(),
                        wallet.getUserId(),
                        newBalance,
                        amount,
                        context,
                        kind.name(),
                        balance.getAvailableSats(),
                        balance.getLockedSats(),
                        balance.getPendingSats(),
                        balance.getObservedSats(),
                        primarySats,
                        bucket);
                balanceEventPublisher.publishBalanceUpdateAfterCommit(event);
            });
        } catch (Exception e) {
            log.error("Failed to publish balance update for walletId={}", walletId, e);
        }
    }

    /**
     * Selects the UI's primary amount: observed for WATCH_ONLY, available for spendable kinds.
     *
     * @param kind wallet custody kind
     * @param balance current persisted balance
     * @return primary amount in satoshis
     */
    static long primarySatsFor(KfeWalletKind kind, KfeBalanceEntity balance) {
        if (kind == KfeWalletKind.WATCH_ONLY) {
            return balance.getObservedSats();
        }
        return balance.getAvailableSats();
    }

    /**
     * Selects the UI amount from wallet metadata, defaulting missing kind data to INTERNAL.
     *
     * @param wallet wallet metadata, possibly null
     * @param balance current persisted balance
     * @return primary amount in satoshis
     */
    static long primarySatsFor(KfeWalletEntity wallet, KfeBalanceEntity balance) {
        KfeWalletKind kind = wallet != null && wallet.getKind() != null
                ? wallet.getKind()
                : KfeWalletKind.INTERNAL;
        return primarySatsFor(kind, balance);
    }

}
