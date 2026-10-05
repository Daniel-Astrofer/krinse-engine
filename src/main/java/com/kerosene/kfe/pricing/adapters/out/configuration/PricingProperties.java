package com.kerosene.kfe.pricing.adapters.out.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/** Spring representation of the external {@code kfe.pricing} configuration. */
@ConfigurationProperties(prefix = "kfe.pricing")
/** Spring-bound, versioned fee policy keyed by payment rail and direction. */
public class PricingProperties {

    /** Configuration schema/policy version copied into pricing snapshots for auditability. */
    private int version = 1;
    /** Entries keyed as {@code RAIL-DIRECTION}; missing or disabled entries are not offered as policy. */
    private Map<String, RailPricing> rails = new HashMap<>();

    /** @return configured fee-policy version */
    public int getVersion() {
        return version;
    }

    /** @param version policy version attached to produced snapshots */
    public void setVersion(int version) {
        this.version = version;
    }

    /** @return mutable Spring-bound rail/direction configuration map */
    public Map<String, RailPricing> getRails() {
        return rails;
    }

    /** @param rails policy map; null resets to an empty map instead of leaving the bean unusable */
    public void setRails(Map<String, RailPricing> rails) {
        this.rails = rails == null ? new HashMap<>() : rails;
    }

    /**
     * Returns a configured policy only when the exact key exists and its rail entry is enabled.
     *
     * @param railKey combined rail and direction key, for example {@code ONCHAIN-INBOUND}
     * @return enabled policy or null when absent/disabled
     */
    RailPricing forRailDirection(String railKey) {
        RailPricing pricing = rails.get(railKey);
        return pricing == null || !pricing.isEnabled() ? null : pricing;
    }

    /** Fee rate and optional absolute satoshi bounds for one rail/direction pair. */
    public static class RailPricing {
        /** Percentage fee expressed in basis points; calculator applies this before min/max clamping. */
        private int basisPoints;
        /** Optional minimum Kerosene fee in satoshis; null means no configured floor. */
        private Integer minSats;
        /** Optional maximum Kerosene fee in satoshis; null means no configured ceiling. */
        private Integer maxSats;
        /** Whether this rail/direction entry should be included in the active pricing snapshot. */
        private boolean enabled;

        /** @return configured fee rate in basis points */
        public int getBasisPoints() {
            return basisPoints;
        }

        /** @param basisPoints fee rate in basis points */
        public void setBasisPoints(int basisPoints) {
            this.basisPoints = basisPoints;
        }

        /** @return minimum fee in satoshis, or null when no floor is configured */
        public Integer getMinSats() {
            return minSats;
        }

        /** @param minSats optional minimum fee in satoshis */
        public void setMinSats(Integer minSats) {
            this.minSats = minSats;
        }

        /** @return maximum fee in satoshis, or null when no ceiling is configured */
        public Integer getMaxSats() {
            return maxSats;
        }

        /** @param maxSats optional maximum fee in satoshis */
        public void setMaxSats(Integer maxSats) {
            this.maxSats = maxSats;
        }

        /** @return true when this rail/direction policy is active */
        public boolean isEnabled() {
            return enabled;
        }

        /** @param enabled activates or disables this rail/direction policy entry */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
