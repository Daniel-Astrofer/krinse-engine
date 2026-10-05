package com.kerosene.kfe.adapters.out.integration.vaultmesh;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/**
 * Optional mTLS materials for {@link KfeVaultMeshSettlementClient}.
 * Supports PKCS12 keystore/truststore paths or PEM cert/key/CA paths ({@code kfe.vaultmesh.tls.*}).
 */
public final class KfeVaultMeshTlsSupport {

    /** Prevents construction because TLS setup is provided through stateless factory methods. */
    private KfeVaultMeshTlsSupport() {}

    /**
     * Determines whether TLS is disabled or has one complete supported credential pair configured.
     * When enabled, certificate/key/CA must all be present, or both keystore and truststore paths
     * must be present.
     *
     * @param enabled whether the application requested TLS
     * @param certPath optional PEM client certificate path
     * @param keyPath optional PEM private key path
     * @param caPath optional PEM CA bundle path
     * @param keystorePath optional client keystore path
     * @param truststorePath optional server truststore path
     * @return false when TLS is disabled, true when enabled with a complete credential pair
     * @throws IllegalStateException when TLS is enabled without a complete supported pair
     */
    static boolean tlsConfigured(
            boolean enabled,
            String certPath,
            String keyPath,
            String caPath,
            String keystorePath,
            String truststorePath) {
        if (!enabled) {
            return false;
        }
        boolean pem = isPresent(certPath) && isPresent(keyPath) && isPresent(caPath);
        boolean stores = isPresent(keystorePath) && isPresent(truststorePath);
        if (!pem && !stores) {
            throw new IllegalStateException(
                    "kfe.vaultmesh.tls.enabled=true requires PEM paths "
                            + "(cert-path, key-path, ca-path) or keystore/truststore paths");
        }
        return true;
    }

    /**
     * Builds a TLS context from a complete keystore/truststore pair when supplied, otherwise from
     * PEM certificate/key/CA files. Client identity and server trust remain separate JSSE roles.
     *
     * @param certPath PEM client certificate or chain path
     * @param keyPath PEM or DER RSA private-key path
     * @param caPath PEM CA bundle trusted for the server
     * @param keystorePath optional client keystore path
     * @param keystorePassword client keystore password
     * @param keystoreType client keystore format, usually PKCS12
     * @param truststorePath optional truststore path
     * @param truststorePassword truststore password
     * @param truststoreType truststore format, usually PKCS12
     * @return initialized TLS context with client key managers and remote trust managers
     * @throws IllegalStateException when files, key material, providers, or TLS initialization fail
     */
    public static SSLContext buildSslContext(
            String certPath,
            String keyPath,
            String caPath,
            String keystorePath,
            String keystorePassword,
            String keystoreType,
            String truststorePath,
            String truststorePassword,
            String truststoreType) {
        try {
            KeyManagerFactory kmf;
            TrustManagerFactory tmf;
            if (isPresent(keystorePath) && isPresent(truststorePath)) {
                kmf = keyManagersFromKeystore(keystorePath, keystorePassword, keystoreType);
                tmf = trustManagersFromTruststore(truststorePath, truststorePassword, truststoreType);
            } else {
                kmf = keyManagersFromPem(certPath, keyPath);
                tmf = trustManagersFromPem(caPath);
            }
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
            return sslContext;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to build vault-mesh mTLS SSLContext: " + ex.getMessage(), ex);
        }
    }

    /**
     * Creates an HTTP request factory that installs the supplied SSL context on HTTPS connections,
     * applies hostname verification policy and timeouts, and optionally routes through a proxy.
     *
     * @param sslContext TLS context providing client keys and trusted server certificates
     * @param hostnameVerification whether the default HTTPS hostname verifier is retained
     * @param connectTimeoutMs connection timeout in milliseconds
     * @param readTimeoutMs response-read timeout in milliseconds
     * @param proxy optional explicit HTTP/SOCKS proxy
     * @return configured request factory for Spring's RestTemplate
     */
    public static SimpleClientHttpRequestFactory requestFactory(
            SSLContext sslContext,
            boolean hostnameVerification,
            int connectTimeoutMs,
            int readTimeoutMs,
            Proxy proxy) {
        HostnameVerifier verifier = hostnameVerification
                ? HttpsURLConnection.getDefaultHostnameVerifier()
                : (String hostname, SSLSession session) -> true;
        SimpleClientHttpRequestFactory factory = new TlsRequestFactory(sslContext, verifier);
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        if (proxy != null) {
            factory.setProxy(proxy);
        }
        return factory;
    }

    /**
     * Validates direct/Tor mode and returns a resolved SOCKS proxy for Tor. Tor requires mTLS,
     * a valid SOCKS address/port, and exclusively HTTPS onion destinations; destination DNS remains
     * delegated to SOCKS instead of being resolved by this process.
     *
     * @param transport configured mode, either {@code direct} or {@code tor}; null defaults to direct
     * @param socksHost SOCKS proxy host for Tor mode
     * @param socksPort SOCKS proxy port for Tor mode
     * @param tlsEnabled whether mutual TLS is enabled
     * @param vaultUrls configured Vault destination URLs
     * @return null for direct transport, or resolved SOCKS proxy for Tor
     * @throws IllegalStateException for unsupported mode, missing mTLS/proxy, invalid URL, or DNS failure
     */
    public static Proxy validateTransport(
            String transport,
            String socksHost,
            int socksPort,
            boolean tlsEnabled,
            Collection<String> vaultUrls) {
        String mode = transport == null ? "direct" : transport.trim().toLowerCase();
        if ("direct".equals(mode)) {
            return null;
        }
        if (!"tor".equals(mode)) {
            throw new IllegalStateException("kfe.vaultmesh.transport must be 'direct' or 'tor'");
        }
        if (!tlsEnabled) {
            throw new IllegalStateException("Tor vault-mesh transport requires mTLS");
        }
        if (!isPresent(socksHost) || socksPort < 1 || socksPort > 65535) {
            throw new IllegalStateException(
                    "Tor vault-mesh transport requires a valid kfe.vaultmesh.proxy.socks-host and socks-port");
        }
        if (vaultUrls == null || vaultUrls.isEmpty()) {
            throw new IllegalStateException("Tor vault-mesh transport requires at least one Vault onion URL");
        }
        for (String value : vaultUrls) {
            URI uri;
            try {
                uri = URI.create(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("Invalid Vault URL in Tor mode", exception);
            }
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || host == null
                    || !host.toLowerCase().endsWith(".onion")) {
                throw new IllegalStateException(
                        "Tor vault-mesh transport accepts only https://*.onion Vault URLs");
            }
        }
        // Resolve only the SOCKS proxy itself (for example the Kubernetes Service
        // "tor-onion"). The .onion destination remains remote-resolved by SOCKS.
        // Passing an unresolved proxy address makes the JDK fail before it can
        // connect to Tor, so no Vault request ever leaves the KFE process.
        try {
            InetAddress proxyAddress = InetAddress.getByName(socksHost.trim());
            return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(proxyAddress, socksPort));
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("Unable to resolve Tor SOCKS proxy host", exception);
        }
    }

    /** Loads the client X.509 chain and RSA private key from PEM, then packages them for JSSE. */
    static KeyManagerFactory keyManagersFromPem(String certPath, String keyPath) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<Certificate> chain = new ArrayList<>();
        try (InputStream in = Files.newInputStream(Path.of(certPath))) {
            Collection<? extends Certificate> certs = cf.generateCertificates(in);
            chain.addAll(certs);
        }
        if (chain.isEmpty()) {
            throw new IllegalStateException("kfe.vaultmesh.tls.cert-path contains no certificates: " + certPath);
        }
        PrivateKey privateKey = loadPrivateKey(Path.of(keyPath));
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        char[] password = "vault-mesh".toCharArray();
        keyStore.setKeyEntry("kfe-client", privateKey, password, chain.toArray(Certificate[]::new));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password);
        return kmf;
    }

    /** Loads all certificates in a PEM CA bundle into a JSSE trust store. */
    static TrustManagerFactory trustManagersFromPem(String caPath) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        int i = 0;
        try (InputStream in = Files.newInputStream(Path.of(caPath))) {
            for (Certificate cert : cf.generateCertificates(in)) {
                trustStore.setCertificateEntry("vault-mesh-ca-" + (i++), cert);
            }
        }
        if (i == 0) {
            throw new IllegalStateException("kfe.vaultmesh.tls.ca-path contains no certificates: " + caPath);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        return tmf;
    }

    /** Loads key managers from a password-protected client keystore using its configured format. */
    private static KeyManagerFactory keyManagersFromKeystore(
            String path, String password, String type) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(blankToDefault(type, "PKCS12"));
        char[] pass = nullToEmpty(password).toCharArray();
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            keyStore.load(in, pass);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, pass);
        return kmf;
    }

    /** Loads trust managers from a password-protected truststore using its configured format. */
    private static TrustManagerFactory trustManagersFromTruststore(
            String path, String password, String type) throws Exception {
        KeyStore trustStore = KeyStore.getInstance(blankToDefault(type, "PKCS12"));
        char[] pass = nullToEmpty(password).toCharArray();
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            trustStore.load(in, pass);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        return tmf;
    }

    /** Reads supported RSA PKCS#1/PKCS#8 PEM or raw DER private-key data and creates an RSA key object. */
    static PrivateKey loadPrivateKey(Path keyPath) throws Exception {
        String pem = Files.readString(keyPath, StandardCharsets.US_ASCII);
        if (pem.contains("BEGIN RSA PRIVATE KEY")) {
            byte[] pkcs1 = decodePemBlock(pem, "RSA PRIVATE KEY");
            byte[] pkcs8 = wrapPkcs1RsaInPkcs8(pkcs1);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        }
        if (pem.contains("BEGIN PRIVATE KEY")) {
            byte[] pkcs8 = decodePemBlock(pem, "PRIVATE KEY");
            // Try RSA first (lab scripts); EC would need a different factory.
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        }
        // Raw DER PKCS#8
        byte[] der = Files.readAllBytes(keyPath);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** Minimal PKCS#1 RSA → PKCS#8 wrap (no BouncyCastle). */
    /** Wraps RSA PKCS#1 bytes in the minimal PKCS#8 structure required by the JCA key factory. */
    static byte[] wrapPkcs1RsaInPkcs8(byte[] pkcs1) {
        byte[] rsaOid = new byte[] {
            0x30, 0x0d,
            0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00
        };
        byte[] lenOctets = encodeDerLength(pkcs1.length);
        byte[] oct = new byte[1 + lenOctets.length + pkcs1.length];
        oct[0] = 0x04;
        System.arraycopy(lenOctets, 0, oct, 1, lenOctets.length);
        System.arraycopy(pkcs1, 0, oct, 1 + lenOctets.length, pkcs1.length);

        byte[] version = new byte[] {0x02, 0x01, 0x00};
        int innerLen = version.length + rsaOid.length + oct.length;
        byte[] innerLenOctets = encodeDerLength(innerLen);
        byte[] out = new byte[1 + innerLenOctets.length + innerLen];
        out[0] = 0x30;
        System.arraycopy(innerLenOctets, 0, out, 1, innerLenOctets.length);
        int pos = 1 + innerLenOctets.length;
        System.arraycopy(version, 0, out, pos, version.length);
        pos += version.length;
        System.arraycopy(rsaOid, 0, out, pos, rsaOid.length);
        pos += rsaOid.length;
        System.arraycopy(oct, 0, out, pos, oct.length);
        return out;
    }

    /** Encodes a nonnegative DER length using short form or one/two length octets. */
    private static byte[] encodeDerLength(int length) {
        if (length < 0x80) {
            return new byte[] {(byte) length};
        }
        if (length < 0x100) {
            return new byte[] {(byte) 0x81, (byte) length};
        }
        if (length < 0x10000) {
            return new byte[] {(byte) 0x82, (byte) (length >> 8), (byte) length};
        }
        throw new IllegalArgumentException("DER length too large: " + length);
    }

    /** Extracts and Base64-decodes one labeled PEM block, rejecting missing or reversed delimiters. */
    private static byte[] decodePemBlock(String pem, String label) {
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        int start = pem.indexOf(begin);
        int stop = pem.indexOf(end);
        if (start < 0 || stop < 0 || stop <= start) {
            throw new IllegalStateException("PEM block missing: " + label);
        }
        String b64 = pem.substring(start + begin.length(), stop).replaceAll("\\s", "");
        return Base64.getDecoder().decode(b64);
    }

    /** @return true when a configuration value contains non-whitespace text */
    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /** Maps a nullable password to an empty string without trimming its contents. */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Uses trimmed configuration text when present or returns the provided default unchanged. */
    private static String blankToDefault(String value, String fallback) {
        return isPresent(value) ? value.trim() : fallback;
    }

    /** Parses and returns the first X.509 certificate from a file for focused certificate tests.
     * @param path certificate file path
     * @return parsed X.509 certificate
     * @throws Exception if the file cannot be read or contains no valid X.509 certificate
     */
    static X509Certificate readFirstCert(Path path) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        try (InputStream in = Files.newInputStream(path)) {
            return (X509Certificate) cf.generateCertificate(in);
        }
    }

    /** HTTP request-factory subclass that installs the configured TLS socket and host verifier. */
    private static final class TlsRequestFactory extends SimpleClientHttpRequestFactory {
        /** TLS context providing the client identity and trusted server certificates. */
        private final SSLContext sslContext;
        /** Host identity policy applied to each HTTPS connection. */
        private final HostnameVerifier hostnameVerifier;

        /**
         * @param sslContext initialized client/server TLS context
         * @param hostnameVerifier verifier selected by the request-factory caller
         */
        private TlsRequestFactory(SSLContext sslContext, HostnameVerifier hostnameVerifier) {
            this.sslContext = sslContext;
            this.hostnameVerifier = hostnameVerifier;
        }

        /** Installs TLS state before delegating common HTTP method and connection configuration. */
        @Override
        protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
            if (connection instanceof HttpsURLConnection https) {
                https.setSSLSocketFactory(sslContext.getSocketFactory());
                https.setHostnameVerifier(hostnameVerifier);
            }
            super.prepareConnection(connection, httpMethod);
        }
    }
}
