package com.kerosene.kfe.adapters.out.rail.lightning;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies Lightning outbound destinations beyond pure BOLT11 invoices:
 * BOLT11, LNURL (bech32), Lightning Address (LUD-16), and keysend node pubkey.
 *
 * <p>Also unwraps common wallet wrappers the mobile app may send as-is:
 * {@code lightning:…}, BIP-21 {@code bitcoin:…?lightning=…}, whitespace/newlines.
 */
public final class LightningDestinationClassifier {

    /** Destination families recognized by the classifier for outbound Lightning routing. */
    public enum Kind {
        /** BOLT11 invoice with a recognized Bitcoin network HRP. */
        BOLT11,
        /** LNURL encoded using the LUD-01 bech32 representation. */
        LNURL,
        /** Human-readable LUD-16 address in {@code user@domain} form. */
        LIGHTNING_ADDRESS,
        /** Compressed 33-byte node public key used for spontaneous keysend payment. */
        KEYSEND
    }

    /**
     * Classified destination after supported URI wrappers and whitespace are normalized.
     *
     * @param kind destination family used to choose downstream payment behavior
     * @param value normalized invoice, LNURL, address, or lowercase node public key
     */
    public record Classified(Kind kind, String value) {
    }

    /** Pattern for LUD-16 user and domain syntax; it is syntactic screening, not DNS validation. */
    private static final Pattern LIGHTNING_ADDRESS =
            Pattern.compile("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");
    /** Pattern for a compressed node public key represented by 66 hexadecimal characters. */
    private static final Pattern NODE_PUBKEY_HEX =
            Pattern.compile("^[0-9a-fA-F]{66}$");
    /** BOLT11 body after optional amount HRP — bech32 data. */
    private static final Pattern BOLT11 =
            Pattern.compile("(?i)^(lnbc|lntb|lnbcrt|lnsb|lntbs)[0-9a-z]+$");
    /** Pattern for LNURL bech32 text with the expected LNURL human-readable prefix. */
    private static final Pattern LNURL_BECH32 =
            Pattern.compile("(?i)^lnurl1[0-9a-z]+$");
    /** Pattern that extracts the Lightning parameter from a BIP-21 URI query string. */
    private static final Pattern BIP21_LIGHTNING_PARAM =
            Pattern.compile("(?i)(?:^|[?&])lightning=([^&]+)");

    /** Utility class; instances are unnecessary because classification has no mutable state. */
    private LightningDestinationClassifier() {
    }

    /**
     * Normalizes a user-supplied destination and classifies its supported Lightning form.
     * Prefix fallback handling tolerates scanner-appended junk for BOLT11/LNURL, so callers
     * must still let the payment implementation validate the actual invoice or protocol data.
     *
     * @param raw clipboard, QR, URI, invoice, address, or node-key text
     * @return normalized classification, or null for blank or unsupported input
     */
    public static Classified classify(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = normalize(raw);
        if (value.isEmpty()) {
            return null;
        }

        String lower = value.toLowerCase(Locale.ROOT);

        // BOLT11 invoice (mainnet / testnet / regtest / signet-style prefixes).
        if (BOLT11.matcher(lower).matches()) {
            return new Classified(Kind.BOLT11, lower);
        }
        // Prefix fallback when bech32 has rare chars / truncated samples still start as invoice.
        if (lower.startsWith("lnbc")
                || lower.startsWith("lntb")
                || lower.startsWith("lnbcrt")
                || lower.startsWith("lnsb")
                || lower.startsWith("lntbs")) {
            // Strip trailing non-bech32 junk sometimes appended by scanners.
            String cleaned = lower.replaceAll("[^0-9a-z].*$", "");
            if (BOLT11.matcher(cleaned).matches() || cleaned.length() > 20) {
                return new Classified(Kind.BOLT11, cleaned.isEmpty() ? lower : cleaned);
            }
        }

        // LNURL bech32 (LUD-01).
        if (LNURL_BECH32.matcher(lower).matches() || lower.startsWith("lnurl1")) {
            String cleaned = lower.replaceAll("[^0-9a-z].*$", "");
            return new Classified(Kind.LNURL, cleaned.isEmpty() ? lower : cleaned);
        }

        // Lightning Address (LUD-16): user@domain
        if (LIGHTNING_ADDRESS.matcher(value).matches()) {
            return new Classified(Kind.LIGHTNING_ADDRESS, value);
        }

        // Spontaneous payment (keysend) to node pubkey — compressed 33-byte hex.
        if (NODE_PUBKEY_HEX.matcher(value).matches()) {
            return new Classified(Kind.KEYSEND, value.toLowerCase(Locale.ROOT));
        }

        return null;
    }

    /**
     * Reports whether the input falls into any syntactically recognized outbound category.
     * This is a routing check and does not establish that the destination is payable or valid.
     *
     * @param raw external destination reference
     * @return true when {@link #classify(String)} recognizes a supported form
     */
    public static boolean isValidLightningOutboundReference(String raw) {
        return classify(raw) != null;
    }

    /**
     * Removes invisible whitespace and unwraps supported BIP-21 and Lightning URI wrappers.
     *
     * @param raw original client-supplied destination
     * @return normalized destination text, or an empty string for null/blank input
     */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        // Collapse all whitespace / zero-width chars from QR/clipboard.
        String value = raw
                .replace("\uFEFF", "")
                .replace("\u200B", "")
                .replaceAll("\\s+", "")
                .trim();
        if (value.isEmpty()) {
            return "";
        }

        // BIP-21: bitcoin:addr?amount=…&lightning=lnbc1…
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("bitcoin:") || lower.startsWith("web+bitcoin:")) {
            Matcher m = BIP21_LIGHTNING_PARAM.matcher(value);
            if (m.find()) {
                try {
                    value = java.net.URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    value = m.group(1);
                }
            }
        }

        // URI forms: lightning:LNURL1... / lightning:user@host / lightning://...
        if (value.regionMatches(true, 0, "lightning:", 0, "lightning:".length())) {
            value = value.substring("lightning:".length()).trim();
            if (value.startsWith("//")) {
                value = value.substring(2).trim();
            }
            // lightning:lnbc1...?amount= — keep path only
            int q = value.indexOf('?');
            if (q > 0) {
                value = value.substring(0, q);
            }
        }

        return value.trim();
    }
}
