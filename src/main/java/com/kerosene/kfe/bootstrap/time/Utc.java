package com.kerosene.kfe.bootstrap.time;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Canonical clock for KFE persistence and API timestamps.
 *
 * <p>All financial wall-clock values are stored as UTC {@link LocalDateTime} numbers and exposed
 * to clients as {@link Instant} with an explicit {@code Z} offset so Flutter/mobile convert to the
 * device timezone correctly (America/Sao_Paulo, etc.).
 */
public final class Utc {

    /** Prevents instantiation of this static UTC conversion utility. */
    private Utc() {
    }

    /**
     * Returns the current wall-clock value represented in UTC without a zone attached.
     * Persisted values created here must be interpreted as UTC when converted back to an instant.
     *
     * @return current UTC date and time as a zone-less persistence value
     */
    public static LocalDateTime nowLocal() {
        return LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /**
     * Returns the current point on the UTC timeline.
     *
     * @return current instant in UTC
     */
    public static Instant now() {
        return Instant.now();
    }

    /**
     * Interpret a stored UTC wall-clock {@link LocalDateTime} as an Instant.
     * Call only for values written with {@link #nowLocal()} / {@code LocalDateTime.now(UTC)}.
     */
    public static Instant toInstant(LocalDateTime utcWallClock) {
        if (utcWallClock == null) {
            return null;
        }
        return utcWallClock.atZone(java.time.ZoneOffset.UTC).toInstant();
    }
}
