package com.kerosene.kfe.adapters.in.http.dto.liquidity;

import java.time.Instant;

/**
 * KFE reserve overview returned to administrative clients.
 *
 * <p>Reports assets and liabilities separately so callers can inspect what is owed and what backs
 * those obligations. The derived equity and coverage ratio provide distinct views of the same
 * snapshot; the status is calculated from the underlying reserve policy rather than a displayed total.
 *
 * @param customerLiabilitiesSats customer obligations in satoshis, including balances owed to users
 * @param lockedLiabilitiesSats obligations reserved or locked for in-progress operations
 * @param pendingLiabilitiesSats obligations awaiting final settlement or reconciliation
 * @param onchainReserveAssetsSats confirmed on-chain assets held to back customer obligations
 * @param lightningReserveAssetsSats Lightning assets included in the reserve backing calculation
 * @param unconfirmedAssetsSats assets not yet sufficiently confirmed for ordinary reserve coverage
 * @param encumberedAssetsSats assets pledged, reserved, or otherwise unavailable for free coverage
 * @param equitySats computed reserve assets less customer liabilities, in satoshis
 * @param coverageRatio ratio of eligible reserve assets to liabilities; interpretation follows reserve policy
 * @param snapshotBlockHash chain block hash anchoring the on-chain portion of this snapshot, when available
 * @param snapshotAt instant when the reserve aggregation was captured
 * @param status solvency classification such as {@code SOLVENT}, {@code NEAR_INSOLVENT}, {@code INSOLVENT}, or {@code UNKNOWN}
 */
public record KfeReserveOverviewResponse(
        // --- Liabilities (what we owe users) ---
        /** Customer liabilities in satoshis. */
        long customerLiabilitiesSats,
        /** Locked liabilities in satoshis. */
        long lockedLiabilitiesSats,
        /** Pending liabilities in satoshis. */
        long pendingLiabilitiesSats,

        // --- Assets (what backs the liabilities) ---
        /** Confirmed on-chain reserve assets in satoshis. */
        long onchainReserveAssetsSats,
        /** Lightning reserve assets in satoshis. */
        long lightningReserveAssetsSats,
        /** Unconfirmed assets excluded or discounted by reserve policy. */
        long unconfirmedAssetsSats,
        /** Assets encumbered by another obligation or restriction. */
        long encumberedAssetsSats,

        // --- Derived ---
        /** Net equity in satoshis, derived from eligible assets less liabilities. */
        long equitySats,            // assets - liabilities
        /** Eligible asset coverage divided by liabilities under the reserve calculation policy. */
        double coverageRatio,       // assets / liabilities
        /** Block hash used to anchor chain-derived asset data, when present. */
        String snapshotBlockHash,
        /** Time at which all contributing reserve data was sampled. */
        Instant snapshotAt,
        /** Policy-derived solvency classification for this snapshot. */
        String status               // SOLVENT, NEAR_INSOLVENT, INSOLVENT, UNKNOWN
) {}
