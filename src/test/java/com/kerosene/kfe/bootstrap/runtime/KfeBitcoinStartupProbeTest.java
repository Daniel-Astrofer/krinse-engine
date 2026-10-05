package com.kerosene.kfe.bootstrap.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class KfeBitcoinStartupProbeTest {
    private static final String SENSITIVE = "synthetic credentials and remote body";
    private static final JsonNode SNAPSHOT = new ObjectMapper().createObjectNode()
            .put("chain", "test").put("initialblockdownload", false);

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @ParameterizedTest
    @CsvSource({"0,0,1", "61,0,1", "1,-1,1", "1,5001,1", "1,0,0", "1,0,120001"})
    void rejectsLimitsOutsideContract(int attempts, long delay, long budget) {
        assertThrows(IllegalArgumentException.class, () -> new KfeBitcoinStartupProbe(attempts, delay, budget));
    }

    @Test
    void acceptsBothBoundaryConfigurationsWithoutMakingAnyCall() {
        assertDoesNotThrow(() -> new KfeBitcoinStartupProbe(1, 0, 1));
        assertDoesNotThrow(() -> new KfeBitcoinStartupProbe(60, 5_000, 120_000));
    }

    @Test
    void successfulReadReturnsSameSnapshotExactlyOnceWithoutSleeping() {
        FakeTime time = new FakeTime();
        AtomicInteger calls = new AtomicInteger();
        JsonNode result = probe(time, 5, 2_000, 60_000).read(() -> {
            calls.incrementAndGet();
            return SNAPSHOT;
        });
        assertSame(SNAPSHOT, result);
        assertEquals(1, calls.get());
        assertTrue(time.sleeps.isEmpty());
    }

    @ParameterizedTest
    @MethodSource("transientTransportFailures")
    void retriesRecognizedTransportEvenWhenWrapped(RuntimeException failure) {
        FakeTime time = new FakeTime();
        AtomicInteger calls = new AtomicInteger();
        JsonNode result = probe(time, 3, 10, 1_000).read(() -> {
            if (calls.incrementAndGet() == 1) {
                throw failure;
            }
            return SNAPSHOT;
        });
        assertSame(SNAPSHOT, result);
        assertEquals(2, calls.get());
        assertEquals(List.of(10L), time.sleeps);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    static Stream<RuntimeException> transientTransportFailures() {
        return Stream.of(
                transport(new IOException(SENSITIVE)),
                wrapped(transport(new ConnectException(SENSITIVE))),
                wrapped(transport(new SocketTimeoutException(SENSITIVE))));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void retriesOnlyWarmupOrRecognizedHttpUnavailability(int status) {
        FakeTime time = new FakeTime();
        AtomicInteger calls = new AtomicInteger();
        JsonNode result = probe(time, 3, 10, 1_000).read(() -> {
            if (calls.incrementAndGet() == 1) {
                throw wrapped(http(status, "{\"error\":{\"code\":-28}}"));
            }
            return SNAPSHOT;
        });
        assertSame(SNAPSHOT, result);
        assertEquals(2, calls.get());
        assertEquals(List.of(10L), time.sleeps);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 408, 429, 500, 501})
    void permanentHttpOrOtherRpcFailureIsNeverRetried(int status) {
        assertPermanent(wrapped(http(status, "{\"error\":{\"code\":-1,\"message\":\"" + SENSITIVE + "\"}}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"error\":{\"code\":\"-28\"}}",
            "{\"error\":{\"code\":-28.0}}",
            "{\"error\":{\"code\":true}}",
            "{\"error\":{\"code\":null}}",
            "{\"error\":{\"code\":4294967268}}",
            "{\"error\":{\"code\":-4294967324}}",
            "{\"error\":{\"code\":28}}",
            "{\"error\":{\"message\":\"RPC_IN_WARMUP\"}}",
            "{\"error\":{\"code\":-28}} trailing invalid json",
            "{\"error\":{\"code\":-28}} {}",
            "{\"error\":{\"code\":401,\"code\":-28}}",
            "{\"code\":-28}", "null", "[]", "not-json", ""
    })
    void malformedOrNonIntegralWarmupCodeDoesNotAuthorizeRetry(String body) {
        assertPermanent(wrapped(http(500, body)));
    }

    @Test
    void doesNotClassifyFailureByRemoteMessageOrUnwrappedGenericIo() {
        assertPermanent(new IllegalStateException("ResourceAccessException 503 RPC_IN_WARMUP timeout " + SENSITIVE));
        assertPermanent(wrapped(new ConnectException(SENSITIVE)));
    }

    @ParameterizedTest
    @MethodSource("tlsFailures")
    void tlsOrCertificateFailureAnywhereInTransportChainVetoesRetry(Throwable tlsFailure) {
        assertPermanent(wrapped(transport(new IOException(SENSITIVE, tlsFailure))));
        assertFalse(Thread.currentThread().isInterrupted());
    }

    static Stream<Throwable> tlsFailures() {
        return Stream.of(new SSLException(SENSITIVE), new SSLHandshakeException(SENSITIVE),
                new CertificateException(SENSITIVE), new CertPathBuilderException(SENSITIVE),
                new CertPathValidatorException(SENSITIVE));
    }

    @Test
    void tlsCauseAlsoVetoesNormallyRetryableHttpStatus() {
        var failure = http(503, SENSITIVE);
        failure.initCause(new SSLException(SENSITIVE));
        assertPermanent(wrapped(failure));
    }

    @Test
    void permanentHttpCauseVetoesAnOuterTransportException() {
        assertPermanent(transport(new IOException(SENSITIVE, http(401, SENSITIVE))));
    }

    @ParameterizedTest
    @MethodSource("interruptions")
    void wrappedInterruptionAbortsWithoutRetryAndRestoresFlag(Throwable interruption) {
        FakeTime time = new FakeTime();
        AtomicInteger calls = new AtomicInteger();
        var failure = assertThrows(IllegalStateException.class, () -> probe(time, 3, 10, 1_000).read(() -> {
            calls.incrementAndGet();
            throw wrapped(transport(new IOException(SENSITIVE, interruption)));
        }));
        assertSanitized(failure);
        assertEquals("Bitcoin Core startup read interrupted", failure.getMessage());
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(1, calls.get());
        assertTrue(time.sleeps.isEmpty());
    }

    static Stream<Throwable> interruptions() {
        return Stream.of(new InterruptedException(SENSITIVE), new InterruptedIOException(SENSITIVE),
                new ClosedByInterruptException());
    }

    @Test
    void alreadyInterruptedThreadDoesNotCallRpc() {
        FakeTime time = new FakeTime();
        AtomicInteger calls = new AtomicInteger();
        Thread.currentThread().interrupt();
        var failure = assertThrows(IllegalStateException.class, () -> probe(time, 3, 10, 1_000).read(() -> {
            calls.incrementAndGet();
            return SNAPSHOT;
        }));
        assertSanitized(failure);
        assertEquals(0, calls.get());
        assertTrue(Thread.currentThread().isInterrupted());
        assertTrue(time.sleeps.isEmpty());
    }

    @Test
    void interruptionDuringSuccessfulReadDoesNotReturnSuccess() {
        FakeTime time = new FakeTime();
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> probe(time, 3, 10, 1_000).read(() -> {
            calls.incrementAndGet();
            Thread.currentThread().interrupt();
            return SNAPSHOT;
        }));
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(1, calls.get());
        assertTrue(time.sleeps.isEmpty());
    }

    @Test
    void interruptedSleepPreventsAnotherRpcAndRestoresFlag() {
        FakeTime time = new FakeTime();
        AtomicInteger calls = new AtomicInteger();
        var probe = new KfeBitcoinStartupProbe(3, 10, 1_000, millis -> {
            throw new InterruptedException(SENSITIVE);
        }, time::now);
        var failure = assertThrows(IllegalStateException.class, () -> probe.read(() -> {
            calls.incrementAndGet();
            throw transport(new IOException(SENSITIVE));
        }));
        assertSanitized(failure);
        assertTrue(Thread.currentThread().isInterrupted());
        assertEquals(1, calls.get());
    }

    @Test
    void maximumAttemptsBoundsRetriesWithoutSleepingAfterLastFailure() {
        FakeTime time = new FakeTime();
        AtomicInteger calls = new AtomicInteger();
        var failure = assertThrows(IllegalStateException.class, () -> probe(time, 3, 10, 1_000).read(() -> {
            calls.incrementAndGet();
            throw transport(new IOException(SENSITIVE));
        }));
        assertSanitized(failure);
        assertEquals(3, calls.get());
        assertEquals(List.of(10L, 10L), time.sleeps);
    }

    @Test
    void zeroRetryDelayStillHonorsAttemptLimitWithoutSleeping() {
        FakeTime time = new FakeTime();
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> probe(time, 3, 0, 1_000).read(() -> {
            calls.incrementAndGet();
            throw transport(new IOException(SENSITIVE));
        }));
        assertEquals(3, calls.get());
        assertTrue(time.sleeps.isEmpty());
    }

    @Test
    void timeSpentInFailingRpcCountsAgainstBudget() {
        FakeTime time = new FakeTime();
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> probe(time, 3, 10, 100).read(() -> {
            calls.incrementAndGet();
            time.advance(100);
            throw transport(new IOException(SENSITIVE));
        }));
        assertEquals(1, calls.get());
        assertTrue(time.sleeps.isEmpty());
    }

    @Test
    void sleepIsCappedByRemainingBudgetAndNoRpcStartsAtDeadline() {
        FakeTime time = new FakeTime();
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> probe(time, 3, 500, 100).read(() -> {
            calls.incrementAndGet();
            time.advance(30);
            throw transport(new IOException(SENSITIVE));
        }));
        assertEquals(1, calls.get());
        assertEquals(List.of(70L), time.sleeps);
        assertEquals(TimeUnit.MILLISECONDS.toNanos(100), time.now());
    }

    @Test
    void schedulerOversleepCannotStartAnExtraRpc() {
        FakeTime time = new FakeTime();
        AtomicInteger calls = new AtomicInteger();
        var probe = new KfeBitcoinStartupProbe(3, 10, 100, millis -> {
            time.sleep(millis);
            time.advance(100);
        }, time::now);
        assertThrows(IllegalStateException.class, () -> probe.read(() -> {
            calls.incrementAndGet();
            throw transport(new IOException(SENSITIVE));
        }));
        assertEquals(1, calls.get());
        assertEquals(List.of(10L), time.sleeps);
    }

    @Test
    void callAlreadyInProgressMaySucceedAfterRetryBudgetWithoutReplay() {
        FakeTime time = new FakeTime();
        AtomicInteger calls = new AtomicInteger();
        JsonNode result = probe(time, 3, 10, 100).read(() -> {
            calls.incrementAndGet();
            time.advance(500);
            return SNAPSHOT;
        });
        assertSame(SNAPSHOT, result);
        assertEquals(1, calls.get());
        assertTrue(time.sleeps.isEmpty());
    }

    @Test
    void elapsedSubtractionHandlesSignedNanoTimeWraparound() {
        FakeTime time = new FakeTime();
        time.nanos = Long.MAX_VALUE - 500_000;
        AtomicInteger calls = new AtomicInteger();
        JsonNode result = probe(time, 3, 1, 3).read(() -> {
            if (calls.incrementAndGet() == 1) {
                time.advance(1);
                throw transport(new IOException(SENSITIVE));
            }
            return SNAPSHOT;
        });
        assertSame(SNAPSHOT, result);
        assertEquals(2, calls.get());
        assertEquals(List.of(1L), time.sleeps);
    }

    @Test
    void nullSnapshotIsReturnedOnceForIntegrationValidationWithoutRetry() {
        FakeTime time = new FakeTime();
        AtomicInteger calls = new AtomicInteger();
        assertNull(probe(time, 3, 10, 100).read(() -> {
            calls.incrementAndGet();
            return null;
        }));
        assertEquals(1, calls.get());
        assertTrue(time.sleeps.isEmpty());
    }

    private static void assertPermanent(RuntimeException rpcFailure) {
        FakeTime time = new FakeTime();
        AtomicInteger calls = new AtomicInteger();
        var failure = assertThrows(IllegalStateException.class, () -> probe(time, 3, 10, 1_000).read(() -> {
            calls.incrementAndGet();
            throw rpcFailure;
        }));
        assertSanitized(failure);
        assertEquals("Bitcoin Core startup read unavailable", failure.getMessage());
        assertEquals(1, calls.get());
        assertTrue(time.sleeps.isEmpty());
    }

    private static void assertSanitized(IllegalStateException failure) {
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.toString().contains(SENSITIVE));
    }

    private static KfeBitcoinStartupProbe probe(FakeTime time, int attempts, long delay, long budget) {
        return new KfeBitcoinStartupProbe(attempts, delay, budget, time::sleep, time::now);
    }

    private static ResourceAccessException transport(IOException cause) {
        return new ResourceAccessException(SENSITIVE, cause);
    }

    private static IllegalStateException wrapped(Throwable cause) {
        return new IllegalStateException(SENSITIVE, cause);
    }

    private static RestClientResponseException http(int status, String body) {
        return new RestClientResponseException(SENSITIVE, status, SENSITIVE, new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static final class FakeTime {
        private long nanos;
        private final List<Long> sleeps = new ArrayList<>();

        long now() { return nanos; }

        void advance(long millis) { nanos += TimeUnit.MILLISECONDS.toNanos(millis); }

        void sleep(long millis) {
            sleeps.add(millis);
            advance(millis);
        }
    }
}
