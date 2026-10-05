package com.kerosene.kfe.adapters.out.rail.lnurl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * SSRF protection for LNURL resolution.
 * Validates URL schemes, blocks private/reserved IP ranges,
 * and prevents cross-host redirects.
 */
public final class LnurlSslGuard {

    /** Logger reserved for host-validation diagnostics without recording full callback URLs. */
    private static final Logger log = LoggerFactory.getLogger(LnurlSslGuard.class);

    /** Utility class; all validation is stateless. */
    private LnurlSslGuard() {
    }

    /**
     * Allows only {@code https://} scheme.
     * Allows {@code http://*.onion} only when {@code allowTor} is true.
     *
     * @param url endpoint to validate before making a network request
     * @param allowTor whether validated onion hosts may use plain HTTP over Tor
     * @throws SecurityException when URL is invalid or its scheme is prohibited
     */
    public static void validateScheme(String url, boolean allowTor) throws SecurityException {
        URI uri = safeUri(url);
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new SecurityException("LNURL: missing URL scheme");
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return;
        }
        if ("http".equalsIgnoreCase(scheme) && allowTor && isOnion(uri.getHost())) {
            return;
        }
        throw new SecurityException("LNURL: scheme not allowed");
    }

    /**
     * Resolves the host and rejects if ANY resolved IP falls into a private,
     * loopback, link-local, multicast, or cloud-metadata range.
     *
     * @param url endpoint whose resolved addresses will be checked
     * @throws SecurityException when DNS fails or any returned address is unsafe
     */
    public static void validateRemoteHost(String url) throws SecurityException {
        URI uri = safeUri(url);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SecurityException("LNURL: missing host in URL");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new SecurityException("LNURL: no addresses resolved for host");
            }
            for (InetAddress addr : addresses) {
                validateAddress(addr);
            }
        } catch (UnknownHostException e) {
            throw new SecurityException("LNURL: DNS resolution failed for host", e);
        }
    }

    /**
     * Throws if the redirect target host differs from the original.
     *
     * @param original initially requested URI
     * @param redirected URI supplied by the redirect response
     * @throws SecurityException when either URI is null or host changes
     */
    public static void validateNoHostChange(URI original, URI redirected) throws SecurityException {
        if (original == null || redirected == null) {
            throw new SecurityException("LNURL: null URI in redirect check");
        }
        String origHost = original.getHost();
        String redirHost = redirected.getHost();
        if (origHost == null || redirHost == null || !origHost.equalsIgnoreCase(redirHost)) {
            throw new SecurityException("LNURL: redirect host changed");
        }
    }

    /**
     * Validates the port is 443 (default HTTPS).
     *
     * @param uri URI whose explicit port is being checked
     * @throws SecurityException when the URI is null or its explicit port is not 443
     */
    public static void validatePort(URI uri) throws SecurityException {
        int port = uri.getPort();
        if (port != -1 && port != 443) {
            throw new SecurityException("LNURL: port not allowed");
        }
    }

    // ---- internal helpers ----

    /**
     * Rejects special-purpose addresses and applies explicit private-range checks by IP family.
     *
     * @param addr resolved address to validate
     * @throws SecurityException when the address is local, private, multicast, or metadata-bound
     */
    private static void validateAddress(InetAddress addr) throws SecurityException {
        if (addr.isLoopbackAddress()) {
            throw new SecurityException("LNURL: loopback address blocked");
        }
        if (addr.isLinkLocalAddress()) {
            throw new SecurityException("LNURL: link-local address blocked");
        }
        if (addr.isSiteLocalAddress()) {
            throw new SecurityException("LNURL: site-local address blocked");
        }
        if (addr.isMulticastAddress()) {
            throw new SecurityException("LNURL: multicast address blocked");
        }
        if (addr.isAnyLocalAddress()) {
            throw new SecurityException("LNURL: any-local address blocked");
        }
        if (isCloudMetadata(addr)) {
            throw new SecurityException("LNURL: cloud metadata endpoint blocked");
        }
        // additional private range checks (covers cases InetAddress.isSiteLocalAddress misses)
        if (addr instanceof Inet4Address v4) {
            validateIpv4PrivateRanges(v4);
        } else if (addr instanceof Inet6Address v6) {
            validateIpv6PrivateRanges(v6);
        }
    }

    /**
     * Blocks the RFC1918 IPv4 ranges explicitly checked by this resolver.
     *
     * @param addr resolved IPv4 address
     * @throws SecurityException when address is in 10/8, 172.16/12, or 192.168/16
     */
    private static void validateIpv4PrivateRanges(Inet4Address addr) {
        byte[] octets = addr.getAddress();
        int first = octets[0] & 0xFF;
        int second = octets[1] & 0xFF;
        // 10.0.0.0/8
        if (first == 10) {
            throw new SecurityException("LNURL: private IPv4 blocked (10/8)");
        }
        // 172.16.0.0/12
        if (first == 172 && second >= 16 && second <= 31) {
            throw new SecurityException("LNURL: private IPv4 blocked (172.16/12)");
        }
        // 192.168.0.0/16
        if (first == 192 && second == 168) {
            throw new SecurityException("LNURL: private IPv4 blocked (192.168/16)");
        }
    }

    /**
     * Blocks IPv6 unique-local addresses in {@code fc00::/7}.
     *
     * @param addr resolved IPv6 address
     * @throws SecurityException when address is in the unique-local range
     */
    private static void validateIpv6PrivateRanges(Inet6Address addr) {
        byte[] octets = addr.getAddress();
        int first = octets[0] & 0xFF;
        // fc00::/7 (unique local addresses)
        if ((first & 0xFE) == 0xFC) {
            throw new SecurityException("LNURL: private IPv6 blocked (fc00::/7)");
        }
    }

    /**
     * Identifies the IPv4 cloud metadata address {@code 169.254.169.254}.
     *
     * @param addr resolved IP address
     * @return true only for the recognized IPv4 metadata endpoint
     */
    private static boolean isCloudMetadata(InetAddress addr) {
        if (addr instanceof Inet4Address v4) {
            byte[] octets = v4.getAddress();
            return octets[0] == (byte) 169
                    && octets[1] == (byte) 254
                    && octets[2] == (byte) 169
                    && octets[3] == (byte) 254;
        }
        return false;
    }

    /**
     * Checks the onion hostname suffix used to gate HTTP access for Tor destinations.
     *
     * @param host URI host, possibly null
     * @return true when host ends in {@code .onion}, ignoring case
     */
    static boolean isOnion(String host) {
        return host != null && host.toLowerCase(java.util.Locale.ROOT).endsWith(".onion");
    }

    /**
     * Parses a URI and wraps malformed input as a validation-specific security failure.
     *
     * @param url untrusted URL text
     * @return parsed URI
     * @throws SecurityException when URI syntax is invalid
     */
    private static URI safeUri(String url) {
        try {
            return URI.create(url);
        } catch (Exception e) {
            throw new SecurityException("LNURL: invalid URL format", e);
        }
    }
}
