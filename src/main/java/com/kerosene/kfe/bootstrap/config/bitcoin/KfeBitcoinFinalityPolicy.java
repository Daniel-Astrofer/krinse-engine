package com.kerosene.kfe.bootstrap.config.bitcoin;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Granular Bitcoin finality policy for the deposit lifecycle.
 *
 * <p>Deposit stages: DETECTED → CONFIRMING → CREDITED → FINALIZED
 *
 * <p>Rules:
 * <ol>
 *   <li>Credit at {@code creditConfirmations} makes funds available, monitoring continues.</li>
 *   <li>Monitor until {@code finalityConfirmations} window.</li>
 *   <li>Reorg before credit: remove expectation, notify "deposit not confirmed".</li>
 *   <li>Reorg after credit: lock equivalent amount, create compensating entry.</li>
 *   <li>Risk-based policy by amount (not enforced here — callers evaluate cutoff).</li>
 * </ol>
 */
@ConfigurationProperties(prefix = "bitcoin")
public class KfeBitcoinFinalityPolicy {

    /** Logger for validated finality settings at application startup. */
    private static final Logger log = LoggerFactory.getLogger(KfeBitcoinFinalityPolicy.class);

    /** Mempool visibility (0-conf). Default 0. */
    private int detectedConfirmations = 0;

    /** Confirmations required to credit available_sats. Production must be >= 1. */
    private int creditConfirmations = 3;

    /** Confirmations after which monitoring stops (funds considered irreversible). */
    private int finalityConfirmations = 6;

    /** Extended watch window for reorg detection beyond finality. */
    private int reorgMonitorConfirmations = 12;

    /**
     * Validates ordering and minimums for all deposit lifecycle thresholds at startup.
     *
     * @throws IllegalStateException when thresholds do not satisfy
     *         {@code 0 <= detected} and {@code 1 <= credit <= finality <= reorg-monitor}
     */
    @PostConstruct
    void validateProductionGate() {
        if (detectedConfirmations < 0
                || creditConfirmations < 1
                || finalityConfirmations < creditConfirmations
                || reorgMonitorConfirmations < finalityConfirmations) {
            String msg = "Invalid Bitcoin finality policy: require 0 <= detected, 1 <= credit <= finality "
                    + "<= reorg-monitor. Current detected=" + detectedConfirmations
                    + " credit=" + creditConfirmations
                    + " finality=" + finalityConfirmations
                    + " reorg-monitor=" + reorgMonitorConfirmations;
            log.error(msg);
            throw new IllegalStateException(msg);
        }
        log.info(
                "Bitcoin finality policy: detected={} credit={} finality={} reorg-monitor={}",
                detectedConfirmations,
                creditConfirmations,
                finalityConfirmations,
                reorgMonitorConfirmations);
    }

    /**
     * Indicates whether a transaction has reached the configured detection threshold.
     *
     * @param confirmations observed confirmation count (zero represents mempool visibility)
     * @return true when count meets or exceeds the detection threshold
     */
    public boolean isDetected(int confirmations) {
        return confirmations >= detectedConfirmations;
    }

    /**
     * Indicates whether funds may be credited to the available balance under this policy.
     *
     * @param confirmations observed confirmation count
     * @return true when count meets or exceeds the credit threshold
     */
    public boolean isCreditReady(int confirmations) {
        return confirmations >= creditConfirmations;
    }

    /**
     * Indicates whether the configured finality threshold has been reached.
     *
     * @param confirmations observed confirmation count
     * @return true when count meets or exceeds the finality threshold
     */
    public boolean isFinalized(int confirmations) {
        return confirmations >= finalityConfirmations;
    }

    /**
     * Indicates whether the configured extended reorganization observation window remains open.
     *
     * @param confirmations observed confirmation count
     * @return true while count is below the reorg-monitor threshold
     */
    public boolean isWithinReorgWindow(int confirmations) {
        return confirmations < reorgMonitorConfirmations;
    }

    /**
     * Risk-based required confirmations by amount.
     * <p>Up to {@code lowSats} → 1 conf, between → 3 confs, above → 6 confs.
     *
     * @param amountSats deposit amount being evaluated
     * @param lowSats inclusive lower-risk amount cutoff
     * @param highSats inclusive medium-risk amount cutoff
     * @return 1, 3, or 6 confirmations based on the amount bands
     */
    public int requiredConfirmationsForAmount(long amountSats, long lowSats, long highSats) {
        if (amountSats <= lowSats) {
            return 1;
        }
        if (amountSats <= highSats) {
            return 3;
        }
        return 6;
    }

    /** @return confirmation count after which a deposit is considered detected */
    public int getDetectedConfirmations() {
        return detectedConfirmations;
    }

    /** @param detectedConfirmations new detection threshold; startup validation checks its bounds */
    public void setDetectedConfirmations(int detectedConfirmations) {
        this.detectedConfirmations = detectedConfirmations;
    }

    /** @return confirmation count required before funds are credited */
    public int getCreditConfirmations() {
        return creditConfirmations;
    }

    /** @param creditConfirmations new credit threshold; startup validation checks its bounds */
    public void setCreditConfirmations(int creditConfirmations) {
        this.creditConfirmations = creditConfirmations;
    }

    /** @return confirmation count required to reach configured finality */
    public int getFinalityConfirmations() {
        return finalityConfirmations;
    }

    /** @param finalityConfirmations new finality threshold; startup validation checks its bounds */
    public void setFinalityConfirmations(int finalityConfirmations) {
        this.finalityConfirmations = finalityConfirmations;
    }

    /** @return confirmation count at which extended reorg monitoring ends */
    public int getReorgMonitorConfirmations() {
        return reorgMonitorConfirmations;
    }

    /** @param reorgMonitorConfirmations new reorg window; startup validation checks its bounds */
    public void setReorgMonitorConfirmations(int reorgMonitorConfirmations) {
        this.reorgMonitorConfirmations = reorgMonitorConfirmations;
    }
}
