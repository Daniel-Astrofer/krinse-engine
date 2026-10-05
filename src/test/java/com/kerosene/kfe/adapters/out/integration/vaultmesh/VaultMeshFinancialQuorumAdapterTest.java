package com.kerosene.kfe.adapters.out.integration.vaultmesh;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.financial.operations.FinancialQuorumPort;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VaultMeshFinancialQuorumAdapterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final Instant START = Instant.parse("2026-09-19T21:04:00Z");
    private static final String HASH = "ab".repeat(32);
    private static final String CONSTITUTION = "cd".repeat(32);
    // Public, deterministic test-only signing scalar. Never used by production transport.
    private static final String KEY = publicKey(BigInteger.valueOf(3));
    private static final String OTHER_KEY = publicKey(BigInteger.valueOf(5));

    @Test
    void verifiesRealBip340ProofAndCompleteMemberAttribution() throws Exception {
        Fixture fixture = new Fixture();
        var decision = fixture.adapter.requireThresholdConsensus(proposal());
        assertEquals(FinancialQuorumPort.Decision.ACCEPTED, decision.decision());
        assertEquals(2, decision.acceptedMembers().size());
        assertEquals(3, decision.configuredMembers());
        fixture.verifySinglePost();
    }

    @Test
    void legacyDeadlineStartsAfterKeysetAndContextReadsWithoutSecondPreflight() throws Exception {
        Fixture fixture = new Fixture();
        fixture.deposits = url -> {
            fixture.clock.advanceTo(START.plusSeconds(60));
            return deposit(KEY);
        };
        fixture.context = () -> {
            fixture.clock.advanceTo(START.plusSeconds(100));
            return context();
        };
        fixture.proof = body -> {
            assertEquals(START.plusSeconds(100).toEpochMilli(), body.get("submitted_at_epoch_ms"));
            assertEquals(START.plusSeconds(220).toEpochMilli(), body.get("expires_at_epoch_ms"));
            return validProof(body);
        };

        assertEquals(2, fixture.adapter.requireHealthyUnanimousConsensus(HASH).acceptedNodes());
        verify(fixture.reads, atMost(3)).exchange(contains("/bitcoin/deposit"), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(String.class));
        fixture.verifySinglePost();
    }

    @Test
    void thirdSlowMemberDoesNotDelayTwoMatchingMembers() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch started = new CountDownLatch(3);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        fixture.deposits = url -> {
            started.countDown();
            try {
                if (!started.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("fanout did not start");
                if (url.startsWith("https://v3")) releaseSlow.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResourceAccessException("test cancelled");
            }
            return deposit(KEY);
        };
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> fixture.adapter.requireThresholdConsensus(proposal()));
        } finally {
            releaseSlow.countDown();
        }
    }

    @Test
    void sameKeyInTwoFieldsOnOneMemberIsOnlyOneVote() throws Exception {
        Fixture fixture = new Fixture();
        fixture.deposits = url -> {
            if (url.startsWith("https://v1")) return deposit(KEY);
            throw new ResourceAccessException("test offline");
        };
        assertThrows(IllegalStateException.class, () -> fixture.adapter.requireThresholdConsensus(proposal()));
        verifyNoInteractions(fixture.proofs);
    }

    @Test
    void differingKeysAndAddressOnlyResponsesCannotFormThreshold() throws Exception {
        Fixture fixture = new Fixture();
        fixture.deposits = url -> url.startsWith("https://v1") ? deposit(KEY)
                : url.startsWith("https://v2") ? deposit(OTHER_KEY) : "{\"address\":\"tb1pfixture\"}";
        assertThrows(IllegalStateException.class, () -> fixture.adapter.requireThresholdConsensus(proposal()));
        verifyNoInteractions(fixture.proofs);
    }

    @Test
    void duplicateUrlCannotCreateTwoMembers() throws Exception {
        Fixture fixture = new Fixture();
        assertThrows(IllegalStateException.class, () -> new VaultMeshFinancialQuorumAdapter(
                fixture.reads, fixture.proofs, JSON, "https://v1", "https://v1,https://v1/",
                3, 2, fixture.clock, 120000, 1000));
    }

    @Test
    void expiryDuringPreflightDoesNotSendPostOrExtendCallerDeadline() throws Exception {
        Fixture fixture = new Fixture();
        fixture.deposits = url -> {
            fixture.clock.now = proposal().expiresAt();
            return deposit(KEY);
        };
        assertThrows(IllegalStateException.class, () -> fixture.adapter.requireThresholdConsensus(proposal()));
        verifyNoInteractions(fixture.proofs);
    }

    @Test
    void proofReceivedAtExpiryIsRejected() throws Exception {
        Fixture fixture = new Fixture();
        fixture.proof = body -> {
            fixture.clock.now = proposal().expiresAt();
            return validProof(body);
        };
        assertThrows(IllegalStateException.class, () -> fixture.adapter.requireThresholdConsensus(proposal()));
        fixture.verifySinglePost();
    }

    @Test
    void initiallyExpiredProposalDoesNotAccessTransport() throws Exception {
        Fixture fixture = new Fixture();
        fixture.clock.now = proposal().expiresAt();
        assertThrows(IllegalArgumentException.class, () -> fixture.adapter.requireThresholdConsensus(proposal()));
        verifyNoInteractions(fixture.reads, fixture.proofs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"verifying_key", "aggregate_proof", "signed_digest", "constitution_hash",
            "constitution_epoch", "required_threshold", "configured_members", "proposal_hash",
            "accepted_members", "decided_at_epoch_ms"})
    void rejectsUntrustedOrIncoherentProofFields(String field) throws Exception {
        Fixture fixture = new Fixture();
        fixture.proof = body -> {
            Map<String, Object> response = validProof(body);
            response.put(field, switch (field) {
                case "verifying_key" -> OTHER_KEY;
                case "aggregate_proof" -> "00".repeat(64);
                case "signed_digest", "constitution_hash", "proposal_hash" -> "00".repeat(32);
                case "constitution_epoch", "required_threshold", "configured_members" -> 999;
                case "accepted_members" -> List.of("vault-1", "vault-1");
                case "decided_at_epoch_ms" -> proposal().expiresAt().toEpochMilli();
                default -> throw new AssertionError(field);
            });
            return response;
        };
        assertThrows(IllegalStateException.class, () -> fixture.adapter.requireThresholdConsensus(proposal()));
        fixture.verifySinglePost();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 400, 503})
    void diagnosesTransportWithoutLeakingUrlsBodiesOrRetrying(int status) throws Exception {
        Fixture fixture = new Fixture();
        RuntimeException cause = switch (status) {
            case 400 -> new HttpClientErrorException(HttpStatus.BAD_REQUEST, "private-token-body");
            case 503 -> new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE, "private-token-body");
            default -> new ResourceAccessException("https://private-endpoint/private-token-body");
        };
        fixture.proof = body -> { throw cause; };
        Logger logger = (Logger) LoggerFactory.getLogger(VaultMeshFinancialQuorumAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var failure = assertThrows(IllegalStateException.class,
                    () -> fixture.adapter.requireThresholdConsensus(proposal()));
            assertEquals("Vault financial quorum is temporarily unavailable", failure.getMessage());
            assertNull(failure.getCause());
            assertTrue(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("phase=proof") && message.contains("httpStatus=" + status)
                            && message.contains("exceptionClass=" + cause.getClass().getSimpleName())));
            for (var event : appender.list) {
                assertFalse(event.getFormattedMessage().contains("private-"));
                assertNull(event.getThrowableProxy());
            }
            fixture.verifySinglePost();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static FinancialQuorumPort.Proposal proposal() {
        return new FinancialQuorumPort.Proposal(HASH, CONSTITUTION, 3, START, START.plusSeconds(120));
    }

    private static String deposit(String key) {
        return "{\"output_pubkey\":\"" + key + "\",\"xonly_pubkey\":\"" + key + "\"}";
    }

    private static String context() {
        return "{\"constitution_hash\":\"" + CONSTITUTION + "\",\"constitution_epoch\":3}";
    }

    private static Map<String, Object> validProof(Map<String, Object> body) {
        String canonical = "kerosene-financial-quorum-v1|" + body.get("proposal_hash") + "|"
                + body.get("constitution_hash") + "|" + body.get("constitution_epoch") + "|"
                + body.get("submitted_at_epoch_ms") + "|" + body.get("expires_at_epoch_ms");
        byte[] digest = hash(canonical.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> result = new HashMap<>(body);
        result.put("decision", "ACCEPTED");
        result.put("configured_members", 3);
        result.put("required_threshold", 2);
        result.put("signed_digest", HEX.formatHex(digest));
        result.put("verifying_key", KEY);
        result.put("aggregate_proof", signFixture(digest));
        result.put("accepted_members", List.of("vault-1", "vault-2"));
        result.put("rejected_members", List.of());
        result.put("unavailable_members", List.of("vault-3"));
        result.put("decided_at_epoch_ms", body.get("submitted_at_epoch_ms"));
        return result;
    }

    private static String publicKey(BigInteger scalar) {
        return HEX.formatHex(CustomNamedCurves.getByName("secp256k1").getG().multiply(scalar)
                .normalize().getAffineXCoord().getEncoded());
    }

    private static String signFixture(byte[] digest) {
        var curve = CustomNamedCurves.getByName("secp256k1");
        BigInteger secret = BigInteger.valueOf(3);
        var publicPoint = curve.getG().multiply(secret).normalize();
        if (publicPoint.getAffineYCoord().toBigInteger().testBit(0)) secret = curve.getN().subtract(secret);
        BigInteger nonce = BigInteger.valueOf(7);
        var noncePoint = curve.getG().multiply(nonce).normalize();
        if (noncePoint.getAffineYCoord().toBigInteger().testBit(0)) nonce = curve.getN().subtract(nonce);
        byte[] r = noncePoint.getAffineXCoord().getEncoded();
        byte[] tag = hash("BIP0340/challenge".getBytes(StandardCharsets.US_ASCII));
        byte[] challenge = new byte[160];
        System.arraycopy(tag, 0, challenge, 0, 32);
        System.arraycopy(tag, 0, challenge, 32, 32);
        System.arraycopy(r, 0, challenge, 64, 32);
        System.arraycopy(HEX.parseHex(KEY), 0, challenge, 96, 32);
        System.arraycopy(digest, 0, challenge, 128, 32);
        BigInteger e = new BigInteger(1, hash(challenge)).mod(curve.getN());
        byte[] scalar = nonce.add(e.multiply(secret)).mod(curve.getN()).toByteArray();
        byte[] signature = new byte[64];
        System.arraycopy(r, 0, signature, 0, 32);
        int length = Math.min(32, scalar.length);
        System.arraycopy(scalar, scalar.length - length, signature, 64 - length, length);
        return HEX.formatHex(signature);
    }

    private static byte[] hash(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception e) { throw new AssertionError(e); }
    }

    private static final class TestClock extends Clock {
        volatile Instant now = START;
        synchronized void advanceTo(Instant next) {
            // A cancelled/late third member must not rewind a concurrently advanced test clock.
            if (next.isAfter(now)) now = next;
        }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class Fixture {
        final TestClock clock = new TestClock();
        final RestTemplate reads = mock(RestTemplate.class);
        final RestTemplate proofs = mock(RestTemplate.class);
        final VaultMeshFinancialQuorumAdapter adapter;
        volatile Function<String, String> deposits = url -> deposit(KEY);
        volatile java.util.function.Supplier<String> context = VaultMeshFinancialQuorumAdapterTest::context;
        volatile Function<Map<String, Object>, Map<String, Object>> proof = VaultMeshFinancialQuorumAdapterTest::validProof;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            when(reads.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenAnswer(call -> {
                        String url = call.getArgument(0);
                        return ResponseEntity.ok(url.contains("/context") ? context.get() : deposits.apply(url));
                    });
            when(proofs.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                    .thenAnswer(call -> JSON.writeValueAsString(proof.apply(
                            (Map<String, Object>) ((HttpEntity<?>) call.getArgument(1)).getBody())));
            adapter = new VaultMeshFinancialQuorumAdapter(reads, proofs, JSON, "https://v1",
                    "https://v1,https://v2,https://v3", 3, 2, clock, 120000, 3000);
        }

        void verifySinglePost() {
            verify(proofs).postForObject(eq("https://v1/v1/financial-quorum"), any(HttpEntity.class), eq(String.class));
        }
    }
}
