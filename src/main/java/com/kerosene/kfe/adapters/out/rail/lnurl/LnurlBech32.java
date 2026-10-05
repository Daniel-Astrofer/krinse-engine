package com.kerosene.kfe.adapters.out.rail.lnurl;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal Bech32 decoder for LNURL (LUD-01): HRP {@code lnurl}, data is the URL bytes.
 */
public final class LnurlBech32 {

    /** Alphabet mapping Bech32 data characters to their five-bit values. */
    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

    /** Utility class; decoding is stateless and exposed through static methods. */
    private LnurlBech32() {
    }

    /**
     * Decodes an LNURL Bech32 string into its embedded HTTP(S) URL.
     * The decoder requires the {@code lnurl} human-readable prefix, valid checksum, strict
     * five-to-eight-bit conversion, UTF-8 payload, and an HTTP or HTTPS scheme.
     *
     * @param lnurl encoded LUD-01 LNURL text
     * @return decoded URL, or null when syntax, checksum, payload, or scheme is invalid
     */
    public static String decodeToUrl(String lnurl) {
        if (lnurl == null || lnurl.isBlank()) {
            return null;
        }
        String lower = lnurl.trim().toLowerCase(Locale.ROOT);
        int sep = lower.lastIndexOf('1');
        if (sep < 1 || sep + 7 > lower.length()) {
            return null;
        }
        String hrp = lower.substring(0, sep);
        if (!"lnurl".equals(hrp)) {
            return null;
        }
        String dataPart = lower.substring(sep + 1);
        int[] data = new int[dataPart.length()];
        for (int i = 0; i < dataPart.length(); i++) {
            int v = CHARSET.indexOf(dataPart.charAt(i));
            if (v < 0) {
                return null;
            }
            data[i] = v;
        }
        if (!verifyChecksum(hrp, data)) {
            return null;
        }
        // strip 6 checksum values
        int[] payload = new int[data.length - 6];
        System.arraycopy(data, 0, payload, 0, payload.length);
        byte[] bytes = convertBits(payload, 5, 8, false);
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        String url = new String(bytes, StandardCharsets.UTF_8).trim();
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            return null;
        }
        return url;
    }

    /**
     * Verifies the Bech32 checksum over expanded HRP and data values.
     *
     * @param hrp human-readable prefix
     * @param data five-bit payload values including the six checksum values
     * @return true when polymod matches the original Bech32 constant
     */
    private static boolean verifyChecksum(String hrp, int[] data) {
        int[] values = new int[hrpExpand(hrp).length + data.length];
        System.arraycopy(hrpExpand(hrp), 0, values, 0, hrpExpand(hrp).length);
        System.arraycopy(data, 0, values, hrpExpand(hrp).length, data.length);
        return polymod(values) == 1;
    }

    /**
     * Expands the HRP into high bits, separator, and low bits as required by Bech32 polymod.
     *
     * @param hrp human-readable prefix
     * @return expanded integer sequence
     */
    private static int[] hrpExpand(String hrp) {
        int[] ret = new int[hrp.length() * 2 + 1];
        for (int i = 0; i < hrp.length(); i++) {
            ret[i] = hrp.charAt(i) >> 5;
        }
        ret[hrp.length()] = 0;
        for (int i = 0; i < hrp.length(); i++) {
            ret[hrp.length() + 1 + i] = hrp.charAt(i) & 31;
        }
        return ret;
    }

    /**
     * Applies the Bech32 generator-polynomial checksum over the supplied values.
     *
     * @param values expanded HRP values followed by data/checksum values
     * @return final checksum state
     */
    private static int polymod(int[] values) {
        int chk = 1;
        int[] generators = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
        for (int v : values) {
            int b = chk >> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ v;
            for (int i = 0; i < 5; i++) {
                if (((b >> i) & 1) != 0) {
                    chk ^= generators[i];
                }
            }
        }
        return chk;
    }

    /**
     * Converts a stream of fixed-width integers between bit groups, rejecting invalid values
     * and nonzero/excess trailing bits when padding is disabled.
     *
     * @param data source values, each constrained to {@code fromBits}
     * @param fromBits number of meaningful bits in each source value
     * @param toBits number of bits in each output value
     * @param pad whether to emit a zero-padded final group
     * @return converted byte values, or null when input range or strict padding rules fail
     */
    private static byte[] convertBits(int[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0;
        int bits = 0;
        List<Integer> out = new ArrayList<>();
        int maxv = (1 << toBits) - 1;
        for (int value : data) {
            if (value < 0 || (value >> fromBits) != 0) {
                return null;
            }
            acc = (acc << fromBits) | value;
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                out.add((acc >> bits) & maxv);
            }
        }
        if (pad) {
            if (bits > 0) {
                out.add((acc << (toBits - bits)) & maxv);
            }
        } else if (bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0) {
            // leftover bits must be zero when not padding (strict)
            if (bits >= fromBits) {
                return null;
            }
            // allow trailing zeros only
        }
        byte[] result = new byte[out.size()];
        for (int i = 0; i < out.size(); i++) {
            result[i] = out.get(i).byteValue();
        }
        return result;
    }
}
