package com.kerosene.kfe.adapters.out.integration.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KfeKeroseneNodeDirectoryTest {

    @Test
    void publishesToHealthyNodesEvenWhenOneFailsWithoutReplayingPost() {
        checkpointDelivery(false);
    }

    @Test
    void reportsFailureWhenNoNodeAcknowledgesCheckpoint() {
        checkpointDelivery(true);
    }

    private void checkpointDelivery(boolean allDown) {
        var client = new org.springframework.web.client.RestTemplate();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(client)
                .ignoreExpectOrder(true).build();
        String base = "https://" + "a".repeat(56) + ".onion:8800";
        String manifest = "{\"network_id\":\"test\",\"plane\":\"vault\",\"members\":[";
        for (char letter : new char[] {'a', 'b', 'c'}) {
            if (letter != 'a') manifest += ",";
            String endpoint = "https://" + String.valueOf(letter).repeat(56) + ".onion:8800";
            manifest += "{\"endpoint\":\"" + endpoint + "\"}";
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(endpoint + "/v1/ledger/challenge"))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                            "{\"challenge\":\"" + "a".repeat(64) + "\"}", org.springframework.http.MediaType.APPLICATION_JSON));
            var post = server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(endpoint + "/v1/ledger/checkpoint"))
                    .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.POST));
            if (allDown || letter == 'c') {
                post.andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());
            } else {
                post.andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"accepted\":true}", org.springframework.http.MediaType.APPLICATION_JSON));
            }
        }
        manifest += "]}";
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(base + "/ready-quorum"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(base + "/v1/membership/current"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        manifest, org.springframework.http.MediaType.APPLICATION_JSON));
        var directory = new KfeKeroseneNodeDirectory(client, new com.fasterxml.jackson.databind.ObjectMapper(), base, "test");
        Runnable publish = () -> directory.publishLedgerCheckpoint(challenge -> java.util.Map.of("challenge", challenge));
        if (allDown) assertThatThrownBy(publish::run).isInstanceOf(IllegalStateException.class);
        else publish.run();
        server.verify();
    }

    @Test
    void authorizesOnlyHttpsOnionHostsAndIgnoresServicePort() {
        String node = KfeKeroseneNodeDirectory.onionHost(
                "https://" + "a".repeat(56) + ".onion:8800");
        String vault = KfeKeroseneNodeDirectory.onionHost(
                "https://" + "a".repeat(56) + ".onion:7801");
        assertThat(vault).isEqualTo(node);
    }

    @Test
    void rejectsClearnetAndKubernetesServiceDns() {
        assertThatThrownBy(() -> KfeKeroseneNodeDirectory.onionHost("https://vault:7801"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> KfeKeroseneNodeDirectory.onionHost("http://example.onion:7801"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> KfeKeroseneNodeDirectory.onionHost("https://short.onion:7801"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsConfiguredVaultAbsentFromVerifiedRoster() {
        String authorized = "a".repeat(56) + ".onion";
        String unknown = "b".repeat(56) + ".onion";

        assertThatThrownBy(() -> KfeKeroseneNodeDirectory.requireAuthorized(
                        List.of("https://" + unknown + ":7801"), Set.of(authorized)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("absent from verified Kerosene Node membership");
    }
    private static final String A = "https://" + "a".repeat(56) + ".onion:8800";
    private static final String B = "https://" + "b".repeat(56) + ".onion:8800";

    @Test
    void fallsBackToConfiguredSeedWhenPrimaryTransportFails() {
        var client = new org.springframework.web.client.RestTemplate();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(client).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(A + "/ready-quorum"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withException(
                        new java.net.SocketTimeoutException("Tor unavailable")));
        expectRoster(server, B, "test");
        var directory = new KfeKeroseneNodeDirectory(client, new com.fasterxml.jackson.databind.ObjectMapper(),
                List.of(A, B), "test");
        directory.requireAuthorized(List.of(B.replace(":8800", ":7701")));
        server.verify();
    }

    @Test
    void doesNotBypassInvalidNetworkWithAnotherSeed() {
        var client = new org.springframework.web.client.RestTemplate();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(client).build();
        expectRoster(server, A, "wrong-network");
        var directory = new KfeKeroseneNodeDirectory(client, new com.fasterxml.jackson.databind.ObjectMapper(),
                List.of(A, B), "test");
        assertThatThrownBy(() -> directory.requireAuthorized(List.of(A)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("invalid");
        server.verify(); // No request to B is allowed.
    }

    @Test
    void doesNotBypassUnauthorizedResponseWithAnotherSeed() {
        var client = new org.springframework.web.client.RestTemplate();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(client).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(A + "/ready-quorum"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest());
        var directory = new KfeKeroseneNodeDirectory(client, new com.fasterxml.jackson.databind.ObjectMapper(),
                List.of(A, B), "test");
        assertThatThrownBy(() -> directory.requireAuthorized(List.of(A)))
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.class);
        server.verify();
    }

    @Test
    void refusesExpiredRosterWhenAllSeedsBecomeUnavailable() {
        var client = new org.springframework.web.client.RestTemplate();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(client).build();
        expectRoster(server, A, "test");
        for (String seed : List.of(A, B)) {
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(seed + "/ready-quorum"))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable());
        }
        var directory = new KfeKeroseneNodeDirectory(client, new com.fasterxml.jackson.databind.ObjectMapper(),
                List.of(A, B), "test", java.time.Duration.ZERO);
        directory.requireAuthorized(List.of(A));
        assertThatThrownBy(() -> directory.requireAuthorized(List.of(A)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No configured Node seed");
        assertThatThrownBy(() -> directory.requireAuthorized(List.of(A)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("deferred");
        server.verify();
    }

    @Test
    void certificateValidationFailureDoesNotFallBack() {
        var client = new org.springframework.web.client.RestTemplate();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(client).build();
        var invalid = new javax.net.ssl.SSLHandshakeException("untrusted certificate");
        invalid.initCause(new java.security.cert.CertificateException("wrong identity"));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(A + "/ready-quorum"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withException(invalid));
        var directory = new KfeKeroseneNodeDirectory(client, new com.fasterxml.jackson.databind.ObjectMapper(),
                List.of(A, B), "test");
        assertThatThrownBy(() -> directory.requireAuthorized(List.of(A)))
                .isInstanceOf(org.springframework.web.client.ResourceAccessException.class);
        server.verify();
    }

    @Test
    void rejectsAnInvalidFallbackBeforeMakingRequests() {
        assertThatThrownBy(() -> new KfeKeroseneNodeDirectory(new org.springframework.web.client.RestTemplate(),
                new com.fasterxml.jackson.databind.ObjectMapper(), List.of(A, "http://untrusted"), "test"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static void expectRoster(org.springframework.test.web.client.MockRestServiceServer server,
            String seed, String network) {
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(seed + "/ready-quorum"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(seed + "/v1/membership/current"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"network_id\":\"" + network + "\",\"plane\":\"vault\",\"members\":[{\"endpoint\":\""
                                + seed + "\"}]}", org.springframework.http.MediaType.APPLICATION_JSON));
    }

}
