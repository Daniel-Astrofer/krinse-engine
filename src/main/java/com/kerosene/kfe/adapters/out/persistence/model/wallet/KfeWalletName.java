package com.kerosene.kfe.adapters.out.persistence.model.wallet;

import java.util.Arrays;
import java.text.Normalizer;

/** Supported user-facing names for the standard wallet categories. */
public enum KfeWalletName {
    /** Wallet intended for longer-term investment balances. */
    INVESTMENT("Investimento"),
    /** Wallet intended for routine day-to-day spending. */
    DAILY("Dia a dia"),
    /** Wallet intended for vehicle-related expenses. */
    VEHICLE("Veiculo"),
    /** Wallet intended for future planned expenses. */
    FUTURE_EXPENSES("Futuros gastos");

    /** Canonical localized label returned to clients for this wallet category. */
    private final String label;

    /** Associates a stable enum identifier with its canonical display label.
     * @param label localized label exposed in wallet views
     */
    KfeWalletName(String label) {
        this.label = label;
    }

    /** @return canonical localized display label for this wallet name */
    public String label() {
        return label;
    }

    /**
     * Resolves a wallet category from its enum identifier or canonical display label.
     *
     * <p>Display labels are normalized for case, accents, and punctuation, so equivalent user input
     * maps to one supported enum value. Missing or unsupported labels are rejected rather than
     * silently creating an unknown wallet category.</p>
     *
     * @param value enum name or localized wallet label supplied by a caller
     * @return matching supported wallet category
     * @throws IllegalArgumentException if the value is blank or does not identify a supported name
     */
    public static KfeWalletName fromLabel(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Wallet name is required.");
        }
        String normalized = normalize(value);
        return Arrays.stream(values())
                .filter(name -> name.name().equalsIgnoreCase(value.trim()) || normalize(name.label).equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Wallet name must be one of: Investimento, Dia a dia, Veiculo, Futuros gastos."));
    }

    /**
     * Produces an accent-insensitive comparison key from a display label.
     * @param value label to trim, lowercase, decompose, and normalize to underscore-separated text
     * @return normalized ASCII comparison key
     */
    private static String normalize(String value) {
        String ascii = Normalizer.normalize(value.trim().toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return ascii
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }
}
