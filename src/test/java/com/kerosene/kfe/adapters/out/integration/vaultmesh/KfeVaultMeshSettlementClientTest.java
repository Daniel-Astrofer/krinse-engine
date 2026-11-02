package com.kerosene.kfe.adapters.out.integration.vaultmesh;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import com.kerosene.common.vaultmesh.governance.VaultMeshDayAdvanceResult;
import com.kerosene.common.vaultmesh.governance.VaultMeshDayStatus;
import com.kerosene.common.vaultmesh.intent.VaultMeshIntent;
import com.kerosene.common.vaultmesh.settlement.VaultMeshDepositInfo;
import com.kerosene.common.vaultmesh.intent.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.governance.VaultMeshReshareResult;

import javax.net.ssl.SSLContext;
import java.lang.reflect.Field;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

class KfeVaultMeshSettlementClientTest {

    @Test
    void advancesEveryMemberAndConfirmsCalendarAgreement() throws Exception {
        VaultMeshDayAdvanceResult result = advanceMesh(
                List.of("2099-01-01", "2099-01-01", "2099-01-01"), -1, false);
        assertThat(result.ok()).isTrue();
        assertThat(result.dayEpoch()).isEqualTo("2099-01-01");
    }

    @Test
    void dayAdvanceToleratesOneUnavailableMemberOnlyWithReadbackQuorum() throws Exception {
        VaultMeshDayAdvanceResult result = advanceMesh(
                List.of("2099-01-01", "2099-01-01", "unavailable"), 2, false);
        assertThat(result.ok()).isTrue();
        assertThat(result.dayEpoch()).isEqualTo("2099-01-01");
    }

    @Test
    void successfulPostRepliesCannotReplaceCurrentCalendarQuorum() throws Exception {
        VaultMeshDayAdvanceResult result = advanceMesh(
                List.of("2099-01-01", "2000-01-01", "2000-01-01"), -1, false);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("MESH_DAY_ADVANCE_QUORUM_NOT_CURRENT");
    }

    @Test
    void dayAdvanceFailsClosedWhenNoTwoCalendarsAgree() throws Exception {
        VaultMeshDayAdvanceResult result = advanceMesh(
                List.of("2099-01-01", "2099-01-02", "2000-01-01"), -1, false);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("MESH_DAY_QUORUM_UNAVAILABLE");
    }

    @Test
    void lostAdvanceReplyIsResolvedByReadbackWithoutReplayingPost() throws Exception {
        VaultMeshDayAdvanceResult result = advanceMesh(
                List.of("2099-01-01", "2099-01-01", "2099-01-01"), 0, true);
        assertThat(result.ok()).isTrue();
    }

    private static VaultMeshDayAdvanceResult advanceMesh(
            List<String> readbackDays, int failedPost, boolean lostReply) throws Exception {
        List<String> members = List.of(
                "https://vault-1.test:7801", "https://vault-2.test:7801", "https://vault-3.test:7801");
        KfeVaultMeshSettlementClient client = new KfeVaultMeshSettlementClient(
                new RestTemplateBuilder(), new ObjectMapper(), members.getFirst(), 1000L, 1000L, "",
                false, "", "", "", "", "", "PKCS12", "", "", "PKCS12", true,
                "", 3, 2, String.join(",", members), "direct", "", 9050);
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate(client))
                .ignoreExpectOrder(true).build();
        for (int i = 0; i < members.size(); i++) {
            var post = server.expect(requestTo(members.get(i) + "/v1/day/advance"))
                    .andExpect(method(HttpMethod.POST));
            if (i == failedPost) {
                post.andRespond(lostReply
                        ? withException(new SocketTimeoutException("response lost"))
                        : withStatus(HttpStatus.SERVICE_UNAVAILABLE));
            } else {
                post.andRespond(withSuccess(
                        "{\"day_epoch\":\"2099-01-01\",\"advanced\":true}", MediaType.APPLICATION_JSON));
            }
            var get = server.expect(requestTo(members.get(i) + "/v1/day/current"))
                    .andExpect(method(HttpMethod.GET));
            get.andRespond("unavailable".equals(readbackDays.get(i))
                    ? withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    : withSuccess("{\"day_epoch\":\"" + readbackDays.get(i) + "\"}", MediaType.APPLICATION_JSON));
        }
        VaultMeshDayAdvanceResult result = client.advanceDay();
        server.verify();
        return result;
    }

    @TempDir
    Path tempDir;

    @Test
    void submitIntentRequiresIntentBoundPsbt() {
        KfeVaultMeshSettlementClient client = client();
        VaultMeshIntent intent = new VaultMeshIntent(
                "intent-1", "USERS", "bc1qtest", 12_345L, "policy-a", Instant.ofEpochMilli(1_700_000_000_000L),
                null, null, null, null);

        VaultMeshReceipt receipt = client.submitIntent(intent);

        assertThat(receipt.status()).isEqualTo(VaultMeshReceipt.Status.REJECTED);
        assertThat(receipt.intentId()).isEqualTo("intent-1");
        assertThat(receipt.reasonCode()).isEqualTo("INTENT_BOUND_PSBT_REQUIRED");
    }

    @Test
    void submitIntentNeverCallsLegacySigningRoute() throws Exception {
        KfeVaultMeshSettlementClient client = client();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        VaultMeshIntent intent = new VaultMeshIntent(
                "intent-2", "USERS", "bc1q", 1L, "p", Instant.ofEpochMilli(1L),
                null, null, null, null);

        VaultMeshReceipt receipt = client.submitIntent(intent);

        assertThat(receipt.status()).isEqualTo(VaultMeshReceipt.Status.REJECTED);
        server.verify();
    }

    @Test
    void submitIntentRejectsBlankIntentId() {
        assertThatThrownBy(() ->
                new VaultMeshIntent(" ", "USERS", "x", 1L, "p", Instant.ofEpochMilli(1L),
                        null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intentId required");
    }

    @Test
    void signPsbtRejectsInfraBucketOnTaproot() {
        KfeVaultMeshSettlementClient client = client();
        var receipt = client.signPsbt(new com.kerosene.common.vaultmesh.settlement.VaultMeshPsbtRequest(
                "intent-infra",
                "sess-infra",
                "INFRA",
                "tb1q-infra-ops",
                1_000L,
                "cHNidP8BAHic"));
        assertThat(receipt.status()).isEqualTo(VaultMeshReceipt.Status.REJECTED);
        assertThat(receipt.reasonCode()).isEqualTo("MESH_BUCKET_NOT_SHARED_TAPROOT:INFRA");
    }

    @Test
    void getUsersDepositAddressRejectsNonTb1p() throws Exception {
        KfeVaultMeshSettlementClient client = client();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));

        server.expect(requestTo("https://vault.test:7801/v1/bitcoin/deposit?bucket=USERS"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {"network":"testnet3","address":"tb1qnotp","descriptor":"tr(x)","scheme":"frost-secp256k1-tr-v3","output_pubkey":"aa","xonly_pubkey":"bb"}
                        """,
                        MediaType.APPLICATION_JSON));

        VaultMeshDepositInfo info = client.getUsersDepositAddress();
        assertThat(info).isNull();
        server.verify();
    }

    @Test
    void getUsersDepositAddressAcceptsValidTb1pBech32mAddress() throws Exception {
        KfeVaultMeshSettlementClient client = client();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));

        server.expect(requestTo("https://vault.test:7801/v1/bitcoin/deposit?bucket=USERS"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        """
                        {"network":"testnet3","address":"tb1pcjw0gcg8h4a2232j04am2pcehpxwq8zfejjknrm09g8tngw5k8vqe54uze","descriptor":"tr(x)","scheme":"frost-secp256k1-tr-v3","output_pubkey":"aa","xonly_pubkey":"bb"}
                        """,
                        MediaType.APPLICATION_JSON));

        VaultMeshDepositInfo info = client.getUsersDepositAddress();
        assertThat(info).isNotNull();
        assertThat(info.address()).isEqualTo("tb1pcjw0gcg8h4a2232j04am2pcehpxwq8zfejjknrm09g8tngw5k8vqe54uze");
        assertThat(info.scheme()).isEqualTo("frost-secp256k1-tr-v3");
        server.verify();
    }

    @Test
    void getDayStatusMapsCurrentEpoch() throws Exception {
        KfeVaultMeshSettlementClient client = client();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        server.expect(requestTo("https://vault.test:7801/v1/day/current"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"day_epoch\":\"2099-01-01\"}", MediaType.APPLICATION_JSON));

        VaultMeshDayStatus status = client.getDayStatus();

        assertThat(status.upToDate()).isTrue();
        assertThat(status.dayEpoch()).isEqualTo("2099-01-01");
        server.verify();
    }

    @Test
    void getDayStatusMapsStaleConflict() throws Exception {
        KfeVaultMeshSettlementClient client = client();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));
        server.expect(requestTo("https://vault.test:7801/v1/day/current"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"day_epoch stale: have 2026-07-21, need 2026-07-22\"}"));

        VaultMeshDayStatus status = client.getDayStatus();

        assertThat(status.stale()).isTrue();
        assertThat(status.upToDate()).isFalse();
        assertThat(status.dayEpoch()).isEqualTo("2026-07-21");
        assertThat(status.neededDayEpoch()).isEqualTo("2026-07-22");
        server.verify();
    }

    @Test
    void voteAdvanceAndReshareHitVaultEndpoints() throws Exception {
        KfeVaultMeshSettlementClient client = client();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate(client));

        server.expect(requestTo("https://vault.test:7801/v1/day/vote"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://vault.test:7801/v1/day/advance"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"day_epoch\":\"2026-07-22\",\"advanced\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://vault.test:7801/v1/reshare/trigger"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"reshared\":true,\"policy\":\"daily\",\"reason\":\"kfe-day-rotation\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.voteDay("kfe", "2026-07-22").ok()).isTrue();
        VaultMeshDayAdvanceResult advanced = client.advanceDay();
        assertThat(advanced.ok()).isTrue();
        assertThat(advanced.dayEpoch()).isEqualTo("2026-07-22");
        assertThat(advanced.advanced()).isTrue();
        VaultMeshReshareResult reshare = client.triggerReshare("kfe-day-rotation");
        assertThat(reshare.ok()).isTrue();
        assertThat(reshare.policy()).isEqualTo("daily");
        server.verify();
    }

    @Test
    void tlsClientAlsoRefusesNonPsbtSigning() throws Exception {
        Path certs = materializeLabCerts();
        KfeVaultMeshSettlementClient client = new KfeVaultMeshSettlementClient(
                new RestTemplateBuilder(),
                new ObjectMapper(),
                "https://vault.test:7801",
                1000L,
                1000L,
                "",
                true,
                certs.resolve("vault-client.crt").toString(),
                certs.resolve("vault-client.pkcs8.key").toString(),
                certs.resolve("ca.crt").toString(),
                "",
                "",
                "PKCS12",
                "",
                "",
                "PKCS12",
                true,
                "",
                3,
                2,
                "https://vault.test:7801",
                "direct",
                "",
                9050);
        assertThat(client.tlsEnabled()).isTrue();

        VaultMeshIntent intent = new VaultMeshIntent("intent-tls", "USERS", "bc1q", 1L, "p", Instant.ofEpochMilli(1L),
                null, null, null, null);
        assertThat(client.submitIntent(intent).status()).isEqualTo(VaultMeshReceipt.Status.REJECTED);
    }

    @Test
    void tlsSupportBuildsContextFromPemAndPkcs12() throws Exception {
        Path certs = materializeLabCerts();
        SSLContext pem = KfeVaultMeshTlsSupport.buildSslContext(
                certs.resolve("vault-client.crt").toString(),
                certs.resolve("vault-client.pkcs8.key").toString(),
                certs.resolve("ca.crt").toString(),
                "",
                "",
                "PKCS12",
                "",
                "",
                "PKCS12");
        assertThat(pem).isNotNull();

        SSLContext p12 = KfeVaultMeshTlsSupport.buildSslContext(
                "",
                "",
                "",
                certs.resolve("kfe-client.p12").toString(),
                "changeit",
                "PKCS12",
                certs.resolve("truststore.p12").toString(),
                "changeit",
                "PKCS12");
        assertThat(p12).isNotNull();

        X509Certificate leaf = KfeVaultMeshTlsSupport.readFirstCert(certs.resolve("vault-client.crt"));
        assertThat(leaf.getSubjectX500Principal().getName()).contains("kerosene-kfe-lab");
    }

    @Test
    void tlsEnabledWithoutMaterialsFailsFast() {
        assertThatThrownBy(() -> new KfeVaultMeshSettlementClient(
                        new RestTemplateBuilder(),
                        new ObjectMapper(),
                        "https://vault.test:7801",
                        1000L,
                        1000L,
                        "",
                        true,
                        "",
                        "",
                        "",
                        "",
                        "",
                        "PKCS12",
                        "",
                        "",
                        "PKCS12",
                        true,
                        "",
                        3,
                        2,
                        "https://vault.test:7801",
                        "direct",
                        "",
                        9050))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kfe.vaultmesh.tls.enabled=true");
    }

    @Test
    void torTransportAcceptsOnlyMtlsOnionUrlsAndResolvesTheSocksProxy() {
        java.net.Proxy proxy = KfeVaultMeshTlsSupport.validateTransport(
                "tor", "127.0.0.1", 9050, true, List.of("https://vaultabcdefghijkl.onion:7801"));

        assertThat(proxy.type()).isEqualTo(java.net.Proxy.Type.SOCKS);
        assertThat((java.net.InetSocketAddress) proxy.address())
                .satisfies(address -> {
                    assertThat(address.isUnresolved()).isFalse();
                    assertThat(address.getAddress().isLoopbackAddress()).isTrue();
                    assertThat(address.getPort()).isEqualTo(9050);
                });
    }

    @Test
    void torTransportFailsClosedForClearnetOrMissingMtls() {
        assertThatThrownBy(() -> KfeVaultMeshTlsSupport.validateTransport(
                        "tor", "tor-proxy", 9050, true, List.of("https://vault-1:7801")))
                .hasMessageContaining("https://*.onion");
        assertThatThrownBy(() -> KfeVaultMeshTlsSupport.validateTransport(
                        "tor", "tor-proxy", 9050, false, List.of("https://vaultabcdefghijkl.onion")))
                .hasMessageContaining("requires mTLS");
    }

    private Path materializeLabCerts() throws Exception {
        Path out = tempDir.resolve("mtls-certs");
        Files.createDirectories(out);
        Path script = locateGenScript();
        ProcessBuilder pb = new ProcessBuilder("bash", script.toString());
        pb.environment().put("VAULT_LAB_MTLS_OUT", out.toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        assertThat(finished).as("cert gen timed out").isTrue();
        assertThat(process.exitValue())
                .as("cert gen failed: %s", output)
                .isZero();
        assertThat(out.resolve("vault-client.pkcs8.key")).exists();
        return out;
    }

    private static Path locateGenScript() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path[] candidates = new Path[] {
            cwd.resolve("src/test/resources/tls/gen_lab_mtls_certs.sh"),
            cwd.resolve("scripts/vault/gen_lab_mtls_certs.sh"),
            cwd.resolve("../../scripts/vault/gen_lab_mtls_certs.sh"),
            cwd.resolve("../../../scripts/vault/gen_lab_mtls_certs.sh"),
            cwd.resolve("backend/kerosene-vault/scripts/gen_lab_mtls_certs.sh"),
            cwd.resolve("../kerosene-vault/scripts/gen_lab_mtls_certs.sh"),
            cwd.resolve("../../kerosene-vault/scripts/gen_lab_mtls_certs.sh"),
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.normalize();
            }
        }
        throw new IllegalStateException("gen_lab_mtls_certs.sh not found from cwd=" + cwd);
    }

    private static KfeVaultMeshSettlementClient client() {
        return new KfeVaultMeshSettlementClient(
                new RestTemplateBuilder(),
                new ObjectMapper(),
                "https://vault.test:7801",
                1000L,
                1000L,
                "");
    }

    private static RestTemplate restTemplate(KfeVaultMeshSettlementClient client) throws Exception {
        Field field = KfeVaultMeshSettlementClient.class.getDeclaredField("restTemplate");
        field.setAccessible(true);
        return (RestTemplate) field.get(client);
    }
}
