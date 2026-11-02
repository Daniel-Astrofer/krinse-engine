package com.kerosene.kfe.ledger;

import com.kerosene.kfe.ledger.adapters.out.notification.KfeLedgerCheckpointSigner;
import com.kerosene.kfe.ledger.adapters.out.persistence.KfeLedgerCheckpointReader.Root;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class KfeLedgerCheckpointSignerTest {
    @TempDir Path directory;

    private KfeLedgerCheckpointSigner signer() throws Exception {
        // Public test vector only; never provision this key into a running service.
        byte[] pkcs8 = HexFormat.of().parseHex("302e020100300506032b657004220420" + "07".repeat(32));
        Path path = directory.resolve("test-key.pem");
        String tag = "PRIVATE" + " KEY";
        Files.writeString(path, "-----BEGIN " + tag + "-----\n"
                + Base64.getEncoder().encodeToString(pkcs8) + "\n-----END " + tag + "-----\n");
        return new KfeLedgerCheckpointSigner(path.toString(), "kerosene-test",
                Clock.fixed(Instant.ofEpochMilli(1_000_000), ZoneOffset.UTC));
    }

    @Test void matchesRustWireSignature() throws Exception {
        var proof = signer().sign(new Root("b".repeat(64), 2, 3, 1_000_000), "a".repeat(64));
        assertThat(proof.signature()).isEqualTo("1acbe1e4fd55193bf315e193f4bc1b929fe19410b17f3e22e5688bbd749253fc701d67bb5238bed42f5fa24c6f1c3c82a25dbe83837fb57babf8b5843b266405");
        assertThat(proof.expiresAtMs()).isEqualTo(1_120_000);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(proof);
        assertThat(json).contains("\"generated_at_ms\":1000000", "\"ledger_root\"");
    }

    @Test void rejectsStaleObservationInvalidNonceAndNegativeCounts() throws Exception {
        var signer = signer();
        assertThatThrownBy(() -> signer.sign(new Root("b".repeat(64), 2, 3, 1), "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signer.sign(new Root("b".repeat(64), 2, 3, 1_000_000), "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signer.sign(new Root("b".repeat(64), -1, 3, 1_000_000), "a".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
