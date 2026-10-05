package com.kerosene.kfe.ledger.adapters.out.settlement;

import com.kerosene.kfe.ledger.domain.SolvencyPolicy;
import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentGateSolvencyPort;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementBalanceSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementSolvencySnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementWalletRole;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Solvency port adapter that delegates decisions to the framework-free policy. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class LedgerPaymentGateSolvencyAdapter implements PaymentGateSolvencyPort {
    /** Repository used to read persisted balance buckets for the solvency input snapshot. */
    private final KfeBalanceRepository balances;
    /** Repository used to map wallet identities to customer/system solvency roles. */
    private final KfeWalletRepository wallets;
    /** Feature gate controlling whether the settlement flow invokes reserve qualification. */
    private final boolean enabled;
    /** Pure policy configured with the reserve buffer, coverage floor, and profit treatment. */
    private final SolvencyPolicy policy;

    /**
     * Creates the transaction-bound adapter and constructs the policy from application properties.
     *
     * @param balances balance persistence source
     * @param wallets wallet-kind lookup source
     * @param enabled whether proof-of-reserves qualification is active
     * @param safetyBufferBps required reserve above included liabilities
     * @param minimumCoverageRatio minimum assets-to-liabilities ratio
     * @param reconcileProfit whether system profit is included in the liability calculation
     */
    public LedgerPaymentGateSolvencyAdapter(KfeBalanceRepository balances, KfeWalletRepository wallets,
            @Value("${kfe.reserves.proof-of-reserves.enabled:true}") boolean enabled,
            @Value("${kfe.reserves.proof-of-reserves.safety-buffer-bps:5000}") long safetyBufferBps,
            @Value("${kfe.reserves.proof-of-reserves.minimum-coverage-ratio:1.0}") double minimumCoverageRatio,
            @Value("${kfe.profit.reconcile-with-vault:true}") boolean reconcileProfit) {
        this.balances = balances;
        this.wallets = wallets;
        this.enabled = enabled;
        this.policy = new SolvencyPolicy(new SolvencyPolicy.Config(
                safetyBufferBps, minimumCoverageRatio, reconcileProfit));
    }

    /** Reports the configured feature-gate value without querying persistence. */
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Loads all persisted balance rows and labels each usable row as customer, system profit, or
     * other according to its wallet kind. Rows lacking a wallet identity are omitted; balances are
     * passed as separate buckets so the caller can apply its own role-specific liability rules.
     *
     * @return immutable projection of balances with their settlement wallet roles
     */
    @Override
    public List<SettlementBalanceSnapshot> loadBalances() {
        List<KfeBalanceEntity> rows = balances.findAll();
        var ids = rows.stream().map(KfeBalanceEntity::getId)
                .filter(id -> id != null && id.getWalletId() != null)
                .map(id -> id.getWalletId()).collect(Collectors.toSet());
        Map<UUID, KfeWalletKind> kinds = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Object[] row : wallets.findKindsByIds(ids)) {
                if (row[0] instanceof UUID id && row[1] instanceof KfeWalletKind kind) {
                    kinds.put(id, kind);
                }
            }
        }
        return rows.stream().filter(row -> row.getId() != null && row.getId().getWalletId() != null)
                .map(row -> new SettlementBalanceSnapshot(role(kinds.get(row.getId().getWalletId())),
                        row.getAvailableSats(), row.getPendingSats(), row.getLockedSats(),
                        row.getAutoHoldSats(), row.getObservedSats())).toList();
    }

    /**
     * Evaluates a settlement reserve snapshot with no additional in-flight-withdrawal amount; the
     * incoming totals are expected to have already accounted for the relevant settlement scope.
     *
     * @param customerLiabilitiesSats liabilities owed to customers
     * @param systemProfitSats system profit, subject to the configured reconciliation switch
     * @param eligibleAssetsSats custody assets accepted for backing liabilities
     * @return the payment-execution contract projection of the solvency decision
     */
    @Override
    public SettlementSolvencySnapshot computeSnapshot(long customerLiabilitiesSats,
            long systemProfitSats, long eligibleAssetsSats) {
        var result = policy.evaluate(customerLiabilitiesSats, systemProfitSats, 0L, eligibleAssetsSats);
        return new SettlementSolvencySnapshot(result.solvent(), result.coverageRatio(),
                result.minimumCoverageRatio(), result.totalLiabilitiesSats(),
                result.eligibleAssetsSats(), result.safetyBufferSats());
    }

    /** Maps a persisted wallet kind to the role used when aggregating customer and profit balances. */
    private static SettlementWalletRole role(KfeWalletKind kind) {
        if (kind == KfeWalletKind.CUSTODIAL_ONCHAIN || kind == KfeWalletKind.INTERNAL) {
            return SettlementWalletRole.CUSTOMER;
        }
        return kind == KfeWalletKind.SYSTEM_PROFIT
                ? SettlementWalletRole.SYSTEM_PROFIT : SettlementWalletRole.OTHER;
    }
}
