package com.kerosene.kfe.bootstrap.config.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * Configures standalone HTTP clients for custody providers, Bitcoin Core, and LND REST.
 * External rail clients use bounded timeouts; Bitcoin Core gets a longer read deadline for UTXO scans.
 */
@Configuration
@ConditionalOnProperty(name = "kfe.standalone", havingValue = "true")
public class KfeHttpClientConfiguration {

    /** Logger for the LND TLS mode selected at bean creation. */
    private static final Logger log = LoggerFactory.getLogger(KfeHttpClientConfiguration.class);
    /** Connection timeout shared by standalone outbound HTTP clients. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** Standard response timeout for custody and external rail APIs. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
    /** scantxoutset over testnet UTXO set often exceeds 20s under load. */
    /** Extended Bitcoin Core response timeout for descriptor/UTXO scans. */
    private static final Duration BITCOIND_READ_TIMEOUT = Duration.ofSeconds(120);

    /** @param builder Spring RestTemplate builder @return custody client with standard external-rail timeouts */
    @Bean("custodyRestTemplate")
    public RestTemplate custodyRestTemplate(RestTemplateBuilder builder) {
        return externalRailTemplate(builder);
    }

    /** @param builder Spring RestTemplate builder @return BTCPay client with standard external-rail timeouts */
    @Bean("btcpayRestTemplate")
    public RestTemplate btcpayRestTemplate(RestTemplateBuilder builder) {
        return externalRailTemplate(builder);
    }

    /**
     * Creates Bitcoin Core's RPC client with the longer response timeout required for UTXO scans.
     *
     * @param builder Spring RestTemplate builder
     * @return Bitcoin Core client with bounded connect and extended read timeouts
     */
    @Bean("bitcoindRestTemplate")
    public RestTemplate bitcoindRestTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(BITCOIND_READ_TIMEOUT)
                .build();
    }

    /**
     * LND REST is always HTTPS. Local/dev clusters often use the self-signed LND cert;
     * when {@code lightning.lnd.tls.insecure=true} (or legacy {@code LIGHTNING_LND_TLS_ENABLED=false}
     * mapped to that property), skip certificate verification so KFE can reach LND.
     * In insecure mode both certificate trust and hostname verification are disabled.
     *
     * @param builder Spring RestTemplate builder
     * @param tlsInsecure explicit opt-in to trust-all TLS for LND only
     * @return LND client, configured with standard timeouts and the selected TLS verification policy
     * @throws IllegalStateException when insecure TLS context setup fails
     */
    @Bean("lndRestTemplate")
    public RestTemplate lndRestTemplate(
            RestTemplateBuilder builder,
            @Value("${lightning.lnd.tls.insecure:false}") boolean tlsInsecure) {
        RestTemplate template = externalRailTemplate(builder);
        if (tlsInsecure) {
            applyInsecureTls(template);
            log.info("[LND REST] lndRestTemplate created with TLS_INSECURE=true — certificate verification disabled");
        } else {
            log.warn("[LND REST] lndRestTemplate created with TLS_INSECURE=false — certificate verification IS enabled");
        }
        return template;
    }

    /**
     * Builds a provider HTTP client with the shared connection and response timeouts.
     *
     * @param builder Spring RestTemplate builder
     * @return client with five-second connect and twenty-second read timeout
     */
    private RestTemplate externalRailTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
                .build();
    }

    /**
     * Replaces the LND client's request factory with a TLS context that trusts every certificate
     * and accepts every hostname. This is intentionally opt-in and suitable only for isolated
     * local/test environments with self-signed certificates.
     *
     * @param template LND RestTemplate whose request factory is replaced
     * @throws IllegalStateException when TLS context or request factory setup fails
     */
    private static void applyInsecureTls(RestTemplate template) {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new TrustAllX509TrustManager()
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());
            HostnameVerifier allowAll = (String hostname, SSLSession session) -> true;
            SimpleClientHttpRequestFactory factory = new InsecureTlsRequestFactory(sslContext, allowAll);
            factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
            factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
            template.setRequestFactory(factory);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to configure insecure LND REST TLS", ex);
        }
    }

    /** Trust manager that performs no certificate-chain validation for explicitly insecure LND mode. */
    private static final class TrustAllX509TrustManager implements X509TrustManager {

        /**
         * Accepts any client certificate chain without validation.
         *
         * @param chain presented client certificate chain
         * @param authType client authentication type
         */
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Deliberately empty: insecure mode disables certificate validation.
        }

        /**
         * Accepts any server certificate chain without validation.
         *
         * @param chain presented server certificate chain
         * @param authType server authentication type
         */
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Deliberately empty: insecure mode disables certificate validation.
        }

        /** @return an empty accepted-issuer list because no issuer is checked in this mode */
        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** Request factory that installs the trust-all context and hostname verifier on HTTPS connections. */
    private static final class InsecureTlsRequestFactory extends SimpleClientHttpRequestFactory {

        /** SSL context used to create sockets without certificate-chain verification. */
        private final SSLContext sslContext;
        /** Verifier used to bypass hostname matching in explicitly insecure mode. */
        private final HostnameVerifier hostnameVerifier;

        /**
         * Creates a request factory with the trust-all TLS context and verifier.
         *
         * @param sslContext TLS context with the no-op trust manager
         * @param hostnameVerifier verifier installed on HTTPS connections
         */
        private InsecureTlsRequestFactory(SSLContext sslContext, HostnameVerifier hostnameVerifier) {
            this.sslContext = sslContext;
            this.hostnameVerifier = hostnameVerifier;
        }

        /**
         * Installs insecure TLS behavior for HTTPS connections, then applies standard Spring setup.
         *
         * @param connection connection to configure
         * @param httpMethod selected HTTP method
         * @throws java.io.IOException when base connection preparation fails
         */
        @Override
        protected void prepareConnection(
                java.net.HttpURLConnection connection, String httpMethod) throws java.io.IOException {
            if (connection instanceof HttpsURLConnection https) {
                https.setSSLSocketFactory(sslContext.getSocketFactory());
                https.setHostnameVerifier(hostnameVerifier);
            }
            super.prepareConnection(connection, httpMethod);
        }
    }
}
