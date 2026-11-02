package com.kerosene.kfe.ledger.adapters.out.notification;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.kerosene.kfe.ledger.adapters.out.persistence.KfeLedgerCheckpointReader.Root;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Dedicated Ed25519 authority key. This is not a wallet/custody signing key. */
@Component
@ConditionalOnProperty(name = "kfe.ledger.node-sync.enabled", havingValue = "true")
public class KfeLedgerCheckpointSigner {
    private final PrivateKey key;
    private final String network;
    private final Clock clock;

    public KfeLedgerCheckpointSigner(
            @Value("${kfe.ledger.node-sync.signing-key-path}") String path,
            @Value("${kfe.kerosene-node.network-id:kerosene-testnet3}") String network,
            @Qualifier("ledgerClock") Clock clock) {
        if (!network.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid checkpoint network");
        }
        this.network = network;
        this.clock = clock;
        try {
            String pem = Files.readString(Path.of(path), StandardCharsets.US_ASCII);
            String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            this.key = KeyFactory.getInstance("Ed25519").generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot load dedicated ledger checkpoint key", exception);
        }
    }

    public Checkpoint sign(Root root, String challenge) {
        if (challenge == null || !challenge.matches("[0-9a-f]{64}")
                || !root.ledgerRoot().matches("[0-9a-f]{64}")
                || root.balancesCount() < 0 || root.postingsCount() < 0) {
            throw new IllegalArgumentException("invalid ledger checkpoint fields");
        }
        long now = root.observedAtMs();
        if (now < 0 || now > clock.millis() || clock.millis() - now >= 120_000) {
            throw new IllegalArgumentException("ledger checkpoint observation is stale");
        }
        Checkpoint unsigned = new Checkpoint(network, challenge, now, Math.addExact(now, 120_000),
                root.ledgerRoot(), root.balancesCount(), root.postingsCount(), "");
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(unsigned.signingMessage().getBytes(StandardCharsets.US_ASCII));
            return new Checkpoint(network, challenge, now, unsigned.expiresAtMs(), root.ledgerRoot(),
                    root.balancesCount(), root.postingsCount(), HexFormat.of().formatHex(signer.sign()));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot sign ledger checkpoint", exception);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Checkpoint(String network, String challenge, long generatedAtMs, long expiresAtMs,
            String ledgerRoot, long balancesCount, long postingsCount, String signature) {
        public String signingMessage() {
            return "KEROSENE_KFE_LEDGER_V1\n" + network + "\n" + challenge + "\n"
                    + generatedAtMs + "\n" + expiresAtMs + "\n" + ledgerRoot + "\n"
                    + balancesCount + "\n" + postingsCount + "\n";
        }
    }
}
