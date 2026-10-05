package com.kerosene.kfe.bootstrap.config.financial;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Enforces the only supported KFE runtime: production controls, Bitcoin
 * testnet3, 2-of-3 Vault mesh, Tor and mutual TLS. There is no bypass profile.
 */
@Component
public class KfeVaultMeshGoLiveGuard implements ApplicationRunner {

    /** Logger used after all immutable go-live requirements have passed. */
    private static final Logger log = LoggerFactory.getLogger(KfeVaultMeshGoLiveGuard.class);

    /** Whether the deployment forbids non-mesh signing paths. */
    private final boolean meshOnly;
    /** Whether the VaultMesh integration is enabled. */
    private final boolean vaultMeshEnabled;
    /** Whether the separate MPC signing path is enabled; the supported mode requires false. */
    private final boolean mpcSigningEnabled;
    /** Whether mutual TLS identity is mandatory for Vault communication. */
    private final boolean requireMtls;
    /** Whether Vault client TLS is enabled. */
    private final boolean tlsEnabled;
    /** Legacy API token value; any nonblank token is rejected because identity uses mTLS. */
    private final String apiToken;
    /** Optional PEM client certificate path. */
    private final String tlsCertPath;
    /** Optional PEM private-key path corresponding to the client certificate. */
    private final String tlsKeyPath;
    /** Optional PEM certificate-authority path for Vault server trust. */
    private final String tlsCaPath;
    /** Optional PKCS12 client keystore path. */
    private final String tlsKeystorePath;
    /** Optional truststore path paired with PKCS12 client configuration. */
    private final String tlsTruststorePath;
    /** Required transport mode; the only supported value is Tor. */
    private final String vaultMeshTransport;
    /** Whether server hostname checks remain enabled in TLS validation. */
    private final boolean tlsHostnameVerification;
    /** Configured Bitcoin network, restricted to testnet3/testnet by this guard. */
    private final String bitcoinNetwork;
    /** Whether local Bitcoin Core signing is enabled; supported mesh mode requires false. */
    private final boolean localCoreSignerEnabled;
    /** Number of members in the VaultMesh signing constitution. */
    private final int memberCount;
    /** Required signer threshold in the VaultMesh constitution. */
    private final int threshold;
    /** Expected 32-byte constitution digest in hexadecimal form. */
    private final String constitutionHash;

    /**
     * Captures go-live configuration values and trims string properties before validation.
     *
     * @param meshOnly mesh-only signing policy
     * @param vaultMeshEnabled VaultMesh feature switch
     * @param mpcSigningEnabled standalone MPC signing switch
     * @param requireMtls whether mTLS is mandatory
     * @param tlsEnabled TLS transport switch
     * @param apiToken legacy bearer token property (must be blank)
     * @param tlsCertPath PEM client certificate
     * @param tlsKeyPath PEM client private key
     * @param tlsCaPath PEM trust CA
     * @param tlsKeystorePath PKCS12 client keystore
     * @param tlsTruststorePath PKCS12 server truststore
     * @param vaultMeshTransport selected mesh transport
     * @param tlsHostnameVerification hostname-verification switch
     * @param bitcoinNetwork selected Bitcoin network
     * @param localCoreSignerEnabled local Core signing switch
     * @param memberCount constitution member count
     * @param threshold constitution approval threshold
     * @param constitutionHash expected constitution hash
     */
    public KfeVaultMeshGoLiveGuard(
            @Value("${kfe.vaultmesh.mesh-only:true}") boolean meshOnly,
            @Value("${kfe.vaultmesh.enabled:true}") boolean vaultMeshEnabled,
            @Value("${kfe.mpc.signing-enabled:false}") boolean mpcSigningEnabled,
            @Value("${kfe.vaultmesh.require-mtls:true}") boolean requireMtls,
            @Value("${kfe.vaultmesh.tls.enabled:true}") boolean tlsEnabled,
            @Value("${kfe.vaultmesh.api-token:}") String apiToken,
            @Value("${kfe.vaultmesh.tls.cert-path:}") String tlsCertPath,
            @Value("${kfe.vaultmesh.tls.key-path:}") String tlsKeyPath,
            @Value("${kfe.vaultmesh.tls.ca-path:}") String tlsCaPath,
            @Value("${kfe.vaultmesh.tls.keystore-path:}") String tlsKeystorePath,
            @Value("${kfe.vaultmesh.tls.truststore-path:}") String tlsTruststorePath,
            @Value("${kfe.vaultmesh.transport:tor}") String vaultMeshTransport,
            @Value("${kfe.vaultmesh.tls.hostname-verification:true}") boolean tlsHostnameVerification,
            @Value("${bitcoin.network:testnet3}") String bitcoinNetwork,
            @Value("${quorum.psbt.local-core-signer-enabled:false}") boolean localCoreSignerEnabled,
            @Value("${kfe.vaultmesh.constitution.member-count:3}") int memberCount,
            @Value("${kfe.vaultmesh.constitution.threshold:2}") int threshold,
            @Value("${kfe.vaultmesh.constitution.hash:}") String constitutionHash) {
        this.meshOnly = meshOnly;
        this.vaultMeshEnabled = vaultMeshEnabled;
        this.mpcSigningEnabled = mpcSigningEnabled;
        this.requireMtls = requireMtls;
        this.tlsEnabled = tlsEnabled;
        this.apiToken = blankToEmpty(apiToken);
        this.tlsCertPath = blankToEmpty(tlsCertPath);
        this.tlsKeyPath = blankToEmpty(tlsKeyPath);
        this.tlsCaPath = blankToEmpty(tlsCaPath);
        this.tlsKeystorePath = blankToEmpty(tlsKeystorePath);
        this.tlsTruststorePath = blankToEmpty(tlsTruststorePath);
        this.vaultMeshTransport = blankToEmpty(vaultMeshTransport);
        this.tlsHostnameVerification = tlsHostnameVerification;
        this.bitcoinNetwork = blankToEmpty(bitcoinNetwork);
        this.localCoreSignerEnabled = localCoreSignerEnabled;
        this.memberCount = memberCount;
        this.threshold = threshold;
        this.constitutionHash = blankToEmpty(constitutionHash);
    }

    /**
     * Enforces the supported production topology: testnet3, Tor, mTLS, and a 2-of-3 VaultMesh.
     * Any mismatch prevents the service from starting; PEM or PKCS12 client/trust material is required.
     *
     * @param args Spring Boot application arguments (unused by the policy checks)
     * @throws IllegalStateException when any required configuration is unsafe or incomplete
     */
    @Override
    public void run(ApplicationArguments args) {
        require(vaultMeshEnabled, "kfe.vaultmesh.enabled must be true");
        require(meshOnly, "kfe.vaultmesh.mesh-only must be true");
        require(!mpcSigningEnabled, "kfe.mpc.signing-enabled must be false");
        require(!localCoreSignerEnabled, "quorum.psbt.local-core-signer-enabled must be false");
        require(requireMtls, "kfe.vaultmesh.require-mtls must be true");
        require(tlsEnabled, "kfe.vaultmesh.tls.enabled must be true");
        require(apiToken.isEmpty(), "kfe.vaultmesh.api-token was removed; use mTLS identity");
        require("tor".equalsIgnoreCase(vaultMeshTransport), "kfe.vaultmesh.transport must be tor");
        require(tlsHostnameVerification, "Vault TLS hostname verification must remain enabled");
        require("testnet3".equalsIgnoreCase(bitcoinNetwork) || "testnet".equalsIgnoreCase(bitcoinNetwork),
                "bitcoin.network must be testnet3");
        require(memberCount == 3 && threshold == 2, "Vault constitution must be exactly 2-of-3");
        require(constitutionHash.matches("[0-9a-fA-F]{64}"),
                "kfe.vaultmesh.constitution.hash must be a 32-byte hexadecimal digest");

        boolean pem = !tlsCertPath.isEmpty() && !tlsKeyPath.isEmpty() && !tlsCaPath.isEmpty();
        boolean pkcs12 = !tlsKeystorePath.isEmpty() && !tlsTruststorePath.isEmpty();
        require(pem || pkcs12, "Vault client mTLS PEM or PKCS12 material is required");

        log.info("KFE production guard active: testnet3, 2-of-3 Vault mesh, Tor and mTLS");
    }

    /**
     * Applies one named go-live invariant and fails immediately when it is false.
     *
     * @param condition whether the configured invariant holds
     * @param message property-specific explanation shown on startup failure
     * @throws IllegalStateException when condition is false
     */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Trims a nullable configuration string and maps null to empty text for consistent checks.
     *
     * @param value raw property value
     * @return trimmed value, or empty string when absent
     */
    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
