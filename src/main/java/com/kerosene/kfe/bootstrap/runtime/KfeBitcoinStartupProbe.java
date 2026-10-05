package com.kerosene.kfe.bootstrap.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import javax.net.ssl.SSLException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Applies bounded retries to the read-only Bitcoin Core blockchain-info startup probe.
 * Only transient transport failures and Core's initial-download response are retried;
 * wallet effects are deliberately excluded, interruption is preserved, and terminal errors
 * are sanitized so remote response bodies or credentials are not retained.
 */
final class KfeBitcoinStartupProbe {
    /** Logger for retry attempts; exception details are intentionally omitted. */
    private static final Logger log = LoggerFactory.getLogger(KfeBitcoinStartupProbe.class);
    /** Strict parser used only to recognize Bitcoin Core's retryable initial-download error. */
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    /** Maximum number of times the read-only supplier may be invoked. */
    private final int maxAttempts;
    /** Delay between retry attempts, capped by the remaining total budget. */
    private final long retryDelayMs;
    /** Maximum elapsed time budget represented in monotonic nanoseconds. */
    private final long maxElapsedNanos;
    /** Injectable delay strategy for retry pacing and deterministic callers. */
    private final Sleeper sleeper;
    /** Monotonic clock used to enforce the elapsed-time budget. */
    private final LongSupplier nanoTime;

    /** Interruptible abstraction for the delay between retry attempts. */
    @FunctionalInterface
    interface Sleeper {
        /**
         * Waits for the requested duration unless the current operation is interrupted.
         *
         * @param millis requested delay in milliseconds
         * @throws InterruptedException if the waiting thread is interrupted
         */
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * Creates a probe using the system sleeper and monotonic clock.
     *
     * @param maxAttempts maximum read attempts (1 through 60)
     * @param retryDelayMs delay between retries (0 through 5000 milliseconds)
     * @param maxElapsedMs total elapsed budget (1 through 120000 milliseconds)
     * @throws IllegalArgumentException if any retry bound is outside its supported range
     */
    KfeBitcoinStartupProbe(int maxAttempts, long retryDelayMs, long maxElapsedMs) {
        this(maxAttempts, retryDelayMs, maxElapsedMs, Thread::sleep, System::nanoTime);
    }

    /**
     * Creates a probe with injectable time and waiting dependencies.
     *
     * @param maxAttempts maximum read attempts (1 through 60)
     * @param retryDelayMs delay between retries (0 through 5000 milliseconds)
     * @param maxElapsedMs total elapsed budget (1 through 120000 milliseconds)
     * @param sleeper interruptible retry wait implementation
     * @param nanoTime monotonic clock returning elapsed-time-compatible nanoseconds
     * @throws IllegalArgumentException if retry limits are outside their supported ranges
     * @throws NullPointerException if either injected dependency is null
     */
    KfeBitcoinStartupProbe(int maxAttempts, long retryDelayMs, long maxElapsedMs,
                           Sleeper sleeper, LongSupplier nanoTime) {
        if (maxAttempts < 1 || maxAttempts > 60 || retryDelayMs < 0 || retryDelayMs > 5_000
                || maxElapsedMs < 1 || maxElapsedMs > 120_000) {
            throw new IllegalArgumentException("Invalid Bitcoin Core startup probe limits");
        }
        this.maxAttempts = maxAttempts;
        this.retryDelayMs = retryDelayMs;
        this.maxElapsedNanos = TimeUnit.MILLISECONDS.toNanos(maxElapsedMs);
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    /**
     * Executes the read-only RPC supplier until it succeeds or the retry policy is exhausted.
     * Retryable cases are transport access failures, HTTP 502/503/504, and Core error -28 in
     * an HTTP 500 response. TLS/certificate failures, other statuses, malformed -28 bodies,
     * elapsed-budget exhaustion, and interruption fail without another RPC attempt.
     *
     * @param readOnlyRpc repeatable, side-effect-free RPC read operation
     * @return the first successful JSON snapshot, which may be null for caller validation
     * @throws IllegalStateException with a sanitized message when unavailable or interrupted
     * @throws NullPointerException if the supplier is null
     */
    JsonNode read(Supplier<JsonNode> readOnlyRpc) {
        Objects.requireNonNull(readOnlyRpc, "readOnlyRpc");
        long startedAt = nanoTime.getAsLong();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            abortIfInterrupted();
            if (remainingNanos(startedAt) <= 0) {
                throw unavailable();
            }
            JsonNode snapshot;
            try {
                snapshot = readOnlyRpc.get();
            } catch (RuntimeException exception) {
                List<Throwable> causes = causes(exception);
                if (Thread.currentThread().isInterrupted() || causes.stream().anyMatch(KfeBitcoinStartupProbe::isInterruption)) {
                    throw interrupted();
                }
                if (!isRetryable(causes) || attempt == maxAttempts) {
                    throw unavailable();
                }
                long remaining = remainingNanos(startedAt);
                if (remaining <= 0) {
                    throw unavailable();
                }
                // Flooring avoids sleeping beyond a sub-millisecond remaining budget.
                long sleepMs = Math.min(retryDelayMs, TimeUnit.NANOSECONDS.toMillis(remaining));
                log.warn("Bitcoin Core startup read unavailable; retry attempt={} of={}", attempt + 1, maxAttempts);
                if (sleepMs > 0) {
                    try {
                        sleeper.sleep(sleepMs);
                    } catch (InterruptedException interrupted) {
                        throw interrupted();
                    }
                }
                // Recheck both interruption and the budget before invoking the supplier again.
                continue;
            }
            abortIfInterrupted();
            // A call already in progress retains its existing HTTP timeout; do not replay success.
            return snapshot;
        }
        throw unavailable();
    }

    /**
     * Computes the nonnegative budget remaining using monotonic elapsed time.
     *
     * @param startedAt monotonic timestamp captured before the first attempt
     * @return remaining budget in nanoseconds, or zero after expiry/an invalid elapsed delta
     */
    private long remainingNanos(long startedAt) {
        long elapsed = nanoTime.getAsLong() - startedAt;
        // nanoTime subtraction also handles a normal signed-counter wraparound.
        return elapsed < 0 || elapsed >= maxElapsedNanos ? 0 : maxElapsedNanos - elapsed;
    }

    /**
     * Collects a throwable cause chain while preventing loops from malformed cyclic causes.
     *
     * @param failure outer exception raised by the RPC client
     * @return unique cause chain in outermost-first order
     */
    private static List<Throwable> causes(Throwable failure) {
        List<Throwable> causes = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            causes.add(current);
        }
        return causes;
    }

    /**
     * Identifies interruption signals without treating socket read timeouts as interruption.
     *
     * @param cause throwable from the RPC cause chain
     * @return whether it represents thread or channel interruption
     */
    private static boolean isInterruption(Throwable cause) {
        return cause instanceof InterruptedException || cause instanceof ClosedByInterruptException
                || (cause instanceof InterruptedIOException && !(cause instanceof SocketTimeoutException));
    }

    /**
     * Decides whether the complete cause chain contains a retryable transport/server response.
     * Any TLS or certificate error takes precedence and makes the operation terminal.
     *
     * @param causes unique outermost-first cause chain
     * @return true only when a retryable HTTP response or resource access failure is present
     */
    private static boolean isRetryable(List<Throwable> causes) {
        if (causes.stream().anyMatch(cause -> cause instanceof SSLException || cause instanceof CertificateException
                || cause instanceof CertPathValidatorException || cause instanceof CertPathBuilderException)) {
            return false;
        }
        boolean retryable = false;
        for (Throwable cause : causes) {
            if (cause instanceof RestClientResponseException response) {
                if (!isRetryableResponse(response)) {
                    return false;
                }
                retryable = true;
            } else if (cause instanceof ResourceAccessException) {
                retryable = true;
            }
        }
        return retryable;
    }

    /**
     * Allows gateway and timeout statuses, plus Bitcoin Core's structured warmup error -28.
     * Duplicate JSON keys and trailing content are rejected by the strict mapper.
     *
     * @param response HTTP failure returned by the RPC endpoint
     * @return true when the status/body represents a transient Core startup condition
     */
    private static boolean isRetryableResponse(RestClientResponseException response) {
        int status = response.getStatusCode().value();
        if (status == 502 || status == 503 || status == 504) {
            return true;
        }
        if (status != 500) {
            return false;
        }
        try {
            JsonNode body = ERROR_MAPPER.readTree(response.getResponseBodyAsString());
            JsonNode code = body == null ? null : body.path("error").path("code");
            return code != null && code.isIntegralNumber() && code.canConvertToInt() && code.intValue() == -28;
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    /** Throws the sanitized interruption exception when the current thread is interrupted. */
    private static void abortIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw interrupted();
        }
    }

    /**
     * Restores the interruption flag and creates the stable, sanitized startup failure.
     *
     * @return exception describing interruption without retaining transport exception data
     */
    private static IllegalStateException interrupted() {
        Thread.currentThread().interrupt();
        return new IllegalStateException("Bitcoin Core startup read interrupted");
    }

    /**
     * Creates the terminal availability failure without attaching potentially sensitive causes.
     *
     * @return exception with a stable sanitized message
     */
    private static IllegalStateException unavailable() {
        // Do not retain RPC exceptions: their messages/causes can contain credentials and remote bodies.
        return new IllegalStateException("Bitcoin Core startup read unavailable");
    }
}
