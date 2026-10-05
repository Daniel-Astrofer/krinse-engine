package com.kerosene.kfe.ledger.adapters.out.persistence;

import com.kerosene.kfe.ledger.application.port.out.LedgerAccountPort;
import com.kerosene.kfe.ledger.domain.LedgerBalance;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceRepository;
import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * Maps ledger account operations to the existing PostgreSQL balance row and mandatory transaction.
 * Updates lock the row first and refresh its audit hash/signature from the complete persisted state.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class JpaLedgerAccountAdapter implements LedgerAccountPort {
    /** Persistence repository that supplies pessimistically locked balance rows. */
    private final KfeBalanceRepository repository;
    /** Hash service used to refresh balance integrity metadata after updates. */
    private final KfeHashService hashes;

    /**
     * Creates the adapter with injected persistence and integrity-hash services.
     *
     * @param repository balance repository with the FOR UPDATE lookup
     * @param hashes canonical balance hash service
     */
    @Autowired
    public JpaLedgerAccountAdapter(KfeBalanceRepository repository, KfeHashService hashes) {
        this.repository = repository;
        this.hashes = hashes;
    }

    /**
     * Compatibility constructor using a default hash-service instance.
     *
     * @param repository balance repository with the FOR UPDATE lookup
     */
    public JpaLedgerAccountAdapter(KfeBalanceRepository repository) {
        this(repository, new KfeHashService());
    }

    /**
     * Locks and converts the requested balance row inside the caller's transaction.
     * Blank asset codes default to BTC; a missing row fails rather than creating an account implicitly.
     *
     * @param walletId wallet aggregate identifier
     * @param asset asset code, defaulting to BTC when blank
     * @return immutable domain balance snapshot read under the row lock
     * @throws IllegalArgumentException if the persisted balance does not exist
     */
    @Override
    public LedgerBalance lock(UUID walletId, String asset) {
        String normalizedAsset = asset == null || asset.isBlank() ? "BTC" : asset;
        KfeBalanceEntity entity = repository.findByWalletIdAndAssetForUpdate(walletId, normalizedAsset)
                .orElseThrow(() -> new IllegalArgumentException("KFE balance not found for wallet " + walletId));
        return toDomain(entity);
    }

    /**
     * Copies domain buckets back to the locked entity and updates nonce/hash integrity metadata.
     * The caller must have acquired the same row lock in this transaction.
     *
     * @param balance updated immutable domain snapshot
     * @throws NullPointerException if balance is null
     * @throws IllegalStateException if its row was not locked by the caller
     */
    @Override
    public void save(LedgerBalance balance) {
        Objects.requireNonNull(balance, "balance is required");
        KfeBalanceEntity lockedEntity = repository.findByWalletIdAndAssetForUpdate(
                balance.walletId(), balance.asset())
                .orElseThrow(() -> new IllegalStateException("ledger account was not locked"));
        lockedEntity.setAvailableSats(balance.availableSats());
        lockedEntity.setPendingSats(balance.pendingSats());
        lockedEntity.setLockedSats(balance.lockedSats());
        lockedEntity.setAutoHoldSats(balance.autoHoldSats());
        lockedEntity.setObservedSats(balance.observedSats());
        lockedEntity.setReorgDebtSats(balance.reorgDebtSats());
        lockedEntity.setNonce(balance.version());
        String hash = hashes.balanceHash(lockedEntity);
        lockedEntity.setLastHash(hash);
        lockedEntity.setBalanceSignature(hash);
        repository.save(lockedEntity);
    }

    /**
     * Projects persisted bucket values and version into the immutable domain aggregate.
     *
     * @param entity locked JPA balance entity
     * @return domain snapshot for the ledger application layer
     */
    private static LedgerBalance toDomain(KfeBalanceEntity entity) {
        return new LedgerBalance(entity.getId().getWalletId(), entity.getId().getAsset(),
                entity.getAvailableSats(), entity.getPendingSats(), entity.getLockedSats(),
                entity.getAutoHoldSats(), entity.getObservedSats(), entity.getReorgDebtSats(),
                entity.getNonce());
    }
}
