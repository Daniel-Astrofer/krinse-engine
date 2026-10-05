package com.kerosene.kfe.adapters.in.http.dto.ledger;

import java.util.List;

/** User-scoped dashboard projection containing wallet balances and recent activity.
 * @param wallets wallet summaries visible to the authenticated user
 * @param recentStatement newest statement entries included in the dashboard
 * @param totalSpendableSats sum of currently spendable wallet balances
 * @param totalObservedSats sum of on-chain amounts observed but not yet spendable
 * @param totalVisibleSats combined balance exposed by the dashboard projection
 */
public record KfeDashboardResponse(
        List<KfeDashboardWallet> wallets,
        List<KfeStatementItem> recentStatement,
        long totalSpendableSats,
        long totalObservedSats,
        long totalVisibleSats) {
}
