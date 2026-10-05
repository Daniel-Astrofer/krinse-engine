package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Locale;

/**
 * Immutable settlement policy. Invalid or permissive configuration is rejected at startup.
 * @param lightningRiskGateMode required Lightning risk mode; currently only {@code enforce} is accepted
 * @param porGateEnabled whether the proof-of-reserve solvency check is enforced
 * @param allowSimulatedBalances whether simulation is configured; true is always rejected
 * @param constitutionMemberCount total configured Vault quorum members
 * @param constitutionThreshold minimum accepted quorum acknowledgements
 */
public record SettlementGatePolicy(String lightningRiskGateMode, boolean porGateEnabled,
        boolean allowSimulatedBalances, int constitutionMemberCount, int constitutionThreshold) {
    /** Normalizes risk mode and rejects unsafe risk, simulation, and quorum settings. */
    public SettlementGatePolicy {
        lightningRiskGateMode = lightningRiskGateMode == null
                ? ""
                : lightningRiskGateMode.trim().toLowerCase(Locale.ROOT);
        if (!"enforce".equals(lightningRiskGateMode)) {
            throw new IllegalArgumentException(
                    "kfe.settlement.lightning.risk-gate-mode must be exactly 'enforce'");
        }
        if (allowSimulatedBalances) {
            throw new IllegalArgumentException(
                    "Simulated balances are forbidden; remove the obsolete simulation setting");
        }
        if (constitutionMemberCount < 1 || constitutionThreshold < 1
                || constitutionThreshold > constitutionMemberCount) {
            throw new IllegalArgumentException(
                    "Vault constitution requires member-count >= threshold >= 1; threshold > constitutionMemberCount is invalid");
        }
    }
    /** Reports whether Lightning outbound checks use mandatory enforcement. */
    /** @return true when the validated mode is enforce */
    public boolean enforceLightningRisk() { return "enforce".equals(lightningRiskGateMode); }
}
