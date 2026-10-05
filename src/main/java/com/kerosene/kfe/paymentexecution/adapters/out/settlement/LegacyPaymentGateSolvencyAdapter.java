package com.kerosene.kfe.paymentexecution.adapters.out.settlement;

import com.kerosene.kfe.adapters.out.persistence.model.ledger.KfeBalanceEntity;
import com.kerosene.kfe.adapters.out.persistence.model.wallet.KfeWalletKind;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentGateSolvencyPort;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementBalanceSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementSolvencySnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementWalletRole;
import com.kerosene.kfe.adapters.out.persistence.repository.ledger.KfeBalanceRepository;
import com.kerosene.kfe.adapters.out.persistence.repository.wallet.KfeWalletRepository;
import com.kerosene.kfe.ledger.adapters.in.KfeProofOfReservesService;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Legacy observed-balance proxy, not a live proof of on-chain assets or a globally locked snapshot. */
@Transactional(propagation = Propagation.MANDATORY)
@Deprecated(forRemoval = false)
public class LegacyPaymentGateSolvencyAdapter implements PaymentGateSolvencyPort {
    private final KfeBalanceRepository balances;
    private final KfeWalletRepository wallets;
    private final KfeProofOfReservesService reserves;

    public LegacyPaymentGateSolvencyAdapter(KfeBalanceRepository balances, KfeWalletRepository wallets,
            KfeProofOfReservesService reserves) {
        this.balances = balances; this.wallets = wallets; this.reserves = reserves;
    }

    @Override public boolean isEnabled() { return reserves.isEnabled(); }

    @Override
    public List<SettlementBalanceSnapshot> loadBalances() {
        var rows = balances.findAll();
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

    @Override
    public SettlementSolvencySnapshot computeSnapshot(long customerLiabilitiesSats, long systemProfitSats,
            long eligibleAssetsSats) {
        var snapshot = reserves.computeSnapshot(customerLiabilitiesSats, systemProfitSats, 0L,
                eligibleAssetsSats, eligibleAssetsSats, 0L, null);
        return new SettlementSolvencySnapshot(snapshot.solvent(), snapshot.coverageRatio(),
                snapshot.minimumCoverageRatio(), snapshot.totalLiabilitiesSats(),
                snapshot.eligibleAssetsSats(), snapshot.safetyBufferSats());
    }

    private static SettlementWalletRole role(KfeWalletKind kind) {
        if (kind == KfeWalletKind.CUSTODIAL_ONCHAIN || kind == KfeWalletKind.INTERNAL) {
            return SettlementWalletRole.CUSTOMER;
        }
        return kind == KfeWalletKind.SYSTEM_PROFIT ? SettlementWalletRole.SYSTEM_PROFIT : SettlementWalletRole.OTHER;
    }
}
