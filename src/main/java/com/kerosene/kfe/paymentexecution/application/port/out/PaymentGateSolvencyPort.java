package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.SettlementBalanceSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementSolvencySnapshot;
import java.util.List;

/** Ledger-based proof-of-reserve policy boundary for settlement-gate decisions. */
public interface PaymentGateSolvencyPort {
    /** Reports whether proof-of-reserve evaluation is enabled by runtime policy. */
    /** @return true when the settlement gate must evaluate ledger solvency */
    boolean isEnabled();
    /** Loads cached ledger balance buckets for liabilities and eligible-asset aggregation. */
    /** @return current ledger snapshots; these do not attest live spendable reserve assets */
    List<SettlementBalanceSnapshot> loadBalances();
    /** Applies solvency policy to aggregated customer liabilities, platform profit, and eligible assets. */
    /** @param customerLiabilitiesSats customer liabilities in satoshis @param systemProfitSats system profit balance in satoshis @param eligibleAssetsSats eligible observed assets in satoshis @return policy result including coverage, liabilities, assets, and buffer */
    SettlementSolvencySnapshot computeSnapshot(long customerLiabilitiesSats, long systemProfitSats, long eligibleAssetsSats);
}
