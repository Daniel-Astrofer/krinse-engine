package com.kerosene.kfe.wallet.adapters.out.persistence;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;

import java.util.List;
import java.util.UUID;

/**
 * Manages system internal wallets (FUNDS, PROFIT) (ITEM 10 — profit segregation).
 *
 * <p>SYSTEM_FUNDS and SYSTEM_PROFIT are internal ledger wallets. They are created with
 * empty balances and exist as accounting entries only. No physical UTXO segregation.
 *
 * <p>PROFIT SEGREGATION:
 * <ul>
 *   <li>SUBLEDGER (default): Profit tracked as ledger entry within USERS. No physical separation.</li>
 *   <li>DEDICATED_BUCKET (future): Separate vault PROFIT bucket. Requires mesh support.</li>
 *   <li>PERIODIC_TRANSFER (future): Accrue in subledger, sweep periodically.</li>
 * </ul>
 *
 * <p>INVARIANT: Structural operations (wallet creation, quorum changes, key rotation)
 * MUST NOT debit the USERS bucket. System wallets are for accounting only — they cannot
 * receive user debits.
 *
 * <p>SYSTEM_PROFIT balance is a LIABILITY within USERS until physically segregated.
 * It must be included in solvency calculations.
 */
@Service
public class KfeSystemWalletService {

    /** Asset code assigned to system accounting wallets. */
    public static final String ASSET_BTC = "BTC";

    /** Lifecycle states included when locating an existing system wallet. */
    private static final List<KfeWalletStatus> ACTIVE_SYSTEM_STATUSES = List.of(
            KfeWalletStatus.CREATING,
            KfeWalletStatus.ACTIVE,
            KfeWalletStatus.FROZEN,
            KfeWalletStatus.ROTATING_ADDRESS);

    /** Repository for system wallet lookup and creation. */
    private final KfeWalletRepository walletRepository;
    /** Creates zeroed ledger balances for newly created system wallets. */
    private final KfeBalanceService balanceService;
    /** Hashes the accounting policy marker persisted with system wallets. */
    private final KfeHashService hashService;
    /** Configured synthetic owner for system-only wallet records. */
    private final Long systemUserId;
    /** Display label used when the SYSTEM_FUNDS wallet is first created. */
    private final String fundsLabel;
    /** Display label used when the SYSTEM_PROFIT wallet is first created. */
    private final String profitLabel;
    /** Normalized profit segregation mode controlling accounting interpretation. */
    private final String profitSegregationMode;

    /** Wires persistence, balance, hashing, owner, labels, and profit-segregation configuration. */
    public KfeSystemWalletService(
            KfeWalletRepository walletRepository,
            KfeBalanceService balanceService,
            KfeHashService hashService,
            @Value("${kfe.system.user-id:0}") Long systemUserId,
            @Value("${kfe.system.wallets.funds-label:Kerosene Fundos Globais}") String fundsLabel,
            @Value("${kfe.system.wallets.profit-label:Kerosene Lucro}") String profitLabel,
            @Value("${kfe.profit.segregation-mode:SUBLEDGER}") String profitSegregationMode) {
        this.walletRepository = walletRepository;
        this.balanceService = balanceService;
        this.hashService = hashService;
        this.systemUserId = systemUserId;
        this.fundsLabel = fundsLabel;
        this.profitLabel = profitLabel;
        this.profitSegregationMode = normalizeMode(profitSegregationMode);
    }

    /**
     * Creates the FUNDS and PROFIT system wallets if they do not exist.
     * Both are created with EMPTY balances — accounting entries only.
     *
     * <p>PROFIT SEGREGATION: In SUBLEDGER mode, these wallets exist as ledger entries
     * within the USERS bucket. No physical UTXO segregation is performed.
     */
    /**
     * Idempotently ensures both accounting-only system wallets and their empty balances exist.
     *
     * <p>In SUBLEDGER mode this does not physically segregate Bitcoin UTXOs or debit USERS.
     */
    @Transactional
    public SystemWallets ensureSystemWallets() {
        KfeWalletEntity funds = ensureWallet(KfeWalletKind.SYSTEM_FUNDS, fundsLabel, true);
        KfeWalletEntity profit = ensureWallet(KfeWalletKind.SYSTEM_PROFIT, profitLabel, true);
        return new SystemWallets(funds.getId(), profit.getId());
    }

    /**
     * Returns the SYSTEM_PROFIT wallet ID.
     * SYSTEM_PROFIT is a ledger-based liability — until physically segregated,
     * its balance represents fees collected from users but NOT backed by a
     * separate UTXO pool.
     */
    /** Resolves the latest eligible SYSTEM_PROFIT wallet or fails if bootstrap has not run. */
    @Transactional(readOnly = true)
    public UUID requireProfitWalletId() {
        return walletRepository.findFirstByUserIdAndKindAndStatusInOrderByCreatedAtDesc(
                        systemUserId,
                        KfeWalletKind.SYSTEM_PROFIT,
                        ACTIVE_SYSTEM_STATUSES)
                .map(KfeWalletEntity::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "KFE system profit wallet is not initialized."));
    }

    /** Returns the configured synthetic owner ID for system wallet rows. */
    public Long systemUserId() {
        return systemUserId;
    }

    /**
     * Returns the current profit segregation mode.
     */
    public String profitSegregationMode() {
        return profitSegregationMode;
    }

    /**
     * Returns true if profit is tracked as a subledger within USERS
     * (no physical UTXO segregation).
     */
    /** Returns whether SYSTEM_PROFIT remains a liability inside the USERS subledger. */
    public boolean isProfitSubledger() {
        return "SUBLEDGER".equals(profitSegregationMode);
    }

    /** Reuses the latest eligible wallet of the requested kind or creates a new empty one. */
    private KfeWalletEntity ensureWallet(KfeWalletKind kind, String label, boolean spendable) {
        return walletRepository.findFirstByUserIdAndKindAndStatusInOrderByCreatedAtDesc(
                        systemUserId,
                        kind,
                        ACTIVE_SYSTEM_STATUSES)
                .orElseGet(() -> createWallet(kind, label, spendable));
    }

    /**
     * Creates a system wallet with empty balance.
     * System wallets are INTERNAL — they CANNOT receive user debits.
     * They exist only for accounting attribution.
     * @param kind system accounting role, such as funds or profit
     * @param label configured display label
     * @param spendable stored capability flag; it does not grant user-debit authority
     * @return persisted active system wallet
     */
    private KfeWalletEntity createWallet(KfeWalletKind kind, String label, boolean spendable) {
        KfeWalletEntity wallet = new KfeWalletEntity();
        wallet.setUserId(systemUserId);
        wallet.setKind(kind);
        wallet.setStatus(KfeWalletStatus.ACTIVE);
        wallet.setLabel(label);
        wallet.setAsset(ASSET_BTC);
        wallet.setSpendable(spendable);
        wallet.setQuorumPolicyHash(hashService.sha256("KFE_SYSTEM_WALLET_POLICY|kind=" + kind));
        wallet = walletRepository.save(wallet);
        balanceService.createEmptyBalance(wallet.getId(), wallet.getAsset());
        return wallet;
    }

    /** Defaults blank configuration to SUBLEDGER and canonicalizes other mode labels. */
    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "SUBLEDGER";
        }
        return mode.trim().toUpperCase();
    }

    /**
     * Identities of the two system accounting wallets.
     *
     * @param fundsWalletId SYSTEM_FUNDS wallet identity
     * @param profitWalletId SYSTEM_PROFIT wallet identity
     */
    public record SystemWallets(UUID fundsWalletId, UUID profitWalletId) {
    }
}
