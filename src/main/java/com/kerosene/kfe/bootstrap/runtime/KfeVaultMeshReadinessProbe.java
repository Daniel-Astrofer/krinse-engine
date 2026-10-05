package com.kerosene.kfe.bootstrap.runtime;

import com.kerosene.common.vaultmesh.governance.VaultMeshDayStatus;
import com.kerosene.common.vaultmesh.settlement.VaultMeshDepositInfo;
import com.kerosene.common.vaultmesh.settlement.VaultMeshSettlementPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Maintains a cached, non-blocking readiness view of the financial Vault mesh.
 *
 * <p>The Kubernetes readiness endpoint must remain fast, while the real Vault checks may traverse
 * Tor and mTLS. The scheduled probe performs the expensive checks and publishes only a bounded
 * status code to the health controller.
 */
@Component
@ConditionalOnProperty(name = "kfe.vaultmesh.enabled", havingValue = "true")
public final class KfeVaultMeshReadinessProbe {
    /** Logger for readiness state transitions and sanitized probe failure classes. */
    private static final Logger log = LoggerFactory.getLogger(KfeVaultMeshReadinessProbe.class);
    /** Required deposit key derivation scheme reported by the Vault mesh. */
    private static final String EXPECTED_SCHEME = "frost-secp256k1-tr-v3";

    /** Remote mesh port queried outside the Kubernetes readiness request path. */
    private final VaultMeshSettlementPort vaultMesh;
    /** Maximum accepted age of a successful or failed scheduled snapshot. */
    private final Duration staleAfter;
    /** UTC clock used to timestamp and age cached results. */
    private final Clock clock;
    /** Atomically published status consumed by readiness endpoints. */
    private final AtomicReference<Snapshot> snapshot =
            new AtomicReference<>(new Snapshot(false, "DOWN:NOT_PROBED", Instant.EPOCH));

    /**
     * Constructs the scheduled probe using the configured snapshot freshness window.
     *
     * @param vaultMesh remote financial Vault mesh API
     * @param staleAfterMs maximum age of a cached probe result in milliseconds
     */
    @Autowired
    public KfeVaultMeshReadinessProbe(
            VaultMeshSettlementPort vaultMesh,
            @Value("${kfe.vaultmesh.readiness.stale-after-ms:180000}") long staleAfterMs) {
        this(vaultMesh, staleAfterMs, Clock.systemUTC());
    }

    /**
     * Constructs the probe with an explicit clock for deterministic lifecycle evaluation.
     * Freshness is clamped to at least one millisecond.
     *
     * @param vaultMesh remote financial Vault mesh API
     * @param staleAfterMs maximum cached snapshot age in milliseconds
     * @param clock source of instants for probe and staleness timestamps
     * @throws NullPointerException if the mesh port or clock is null
     */
    KfeVaultMeshReadinessProbe(
            VaultMeshSettlementPort vaultMesh, long staleAfterMs, Clock clock) {
        this.vaultMesh = Objects.requireNonNull(vaultMesh, "vaultMesh");
        this.staleAfter = Duration.ofMillis(Math.max(1L, staleAfterMs));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Performs the remote checks concurrently and atomically publishes a bounded status snapshot.
     * Log output contains only state changes or exception type names, never remote payloads.
     */
    @Scheduled(
            fixedDelayString = "${kfe.vaultmesh.readiness.fixed-delay-ms:60000}",
            initialDelayString = "${kfe.vaultmesh.readiness.initial-delay-ms:5000}")
    public void refresh() {
        Instant checkedAt = clock.instant();
        Snapshot next = probe(checkedAt);
        Snapshot previous = snapshot.getAndSet(next);
        if (!next.status().equals(previous.status())) {
            log.info("Vault mesh readiness changed status={}", next.status());
        }
    }

    /**
     * Returns the latest probe result, downgrading it when its timestamp exceeds the freshness limit.
     * The first unprobed sentinel remains visible until the initial scheduled check completes.
     *
     * @return current immutable readiness snapshot or a stale-status projection
     */
    public Snapshot current() {
        Snapshot current = snapshot.get();
        if (current.checkedAt().equals(Instant.EPOCH)) {
            return current;
        }
        Duration age = Duration.between(current.checkedAt(), clock.instant());
        if (age.isNegative() || age.compareTo(staleAfter) <= 0) {
            return current;
        }
        return new Snapshot(false, "DOWN:PROBE_STALE", current.checkedAt());
    }

    /**
     * Queries day status and independent USERS/CHANNELS deposit keysets concurrently.
     * Readiness requires a fresh day and two valid, distinct testnet keysets; interruption is restored.
     *
     * @param checkedAt instant assigned to every outcome from this probe cycle
     * @return immutable status snapshot representing this cycle's validation result
     */
    private Snapshot probe(Instant checkedAt) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<VaultMeshDayStatus> dayFuture = executor.submit(vaultMesh::getDayStatus);
            Future<VaultMeshDepositInfo> usersFuture =
                    executor.submit(vaultMesh::getUsersDepositAddress);
            Future<VaultMeshDepositInfo> channelsFuture =
                    executor.submit(vaultMesh::getChannelsDepositAddress);

            VaultMeshDayStatus day = dayFuture.get();
            VaultMeshDepositInfo users = usersFuture.get();
            VaultMeshDepositInfo channels = channelsFuture.get();

            String invalidDay = validateDay(day);
            if (invalidDay != null) {
                return new Snapshot(false, invalidDay, checkedAt);
            }
            if (!validKeyset(users)) {
                return new Snapshot(false, "DOWN:USERS_KEYSET_UNAVAILABLE", checkedAt);
            }
            if (!validKeyset(channels)) {
                return new Snapshot(false, "DOWN:CHANNELS_KEYSET_UNAVAILABLE", checkedAt);
            }
            if (Objects.equals(users.address(), channels.address())
                    || Objects.equals(users.outputPubkeyHex(), channels.outputPubkeyHex())) {
                return new Snapshot(false, "DOWN:KEYSETS_NOT_SEPARATED", checkedAt);
            }
            return new Snapshot(true, "UP", checkedAt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Snapshot(false, "DOWN:PROBE_INTERRUPTED", checkedAt);
        } catch (Exception exception) {
            log.warn("Vault mesh readiness probe failed type={}", exception.getClass().getSimpleName());
            return new Snapshot(false, "DOWN:PROBE_FAILED", checkedAt);
        }
    }

    /**
     * Maps absent, errored, stale, or non-current day metadata to a bounded readiness code.
     *
     * @param day Vault mesh day-status response
     * @return failure code when invalid, otherwise {@code null}
     */
    private static String validateDay(VaultMeshDayStatus day) {
        if (day == null || day.error() != null) {
            return "DOWN:DAY_UNAVAILABLE";
        }
        if (day.stale() || !day.upToDate()) {
            return "DOWN:DAY_STALE";
        }
        return null;
    }

    /**
     * Verifies the expected FROST scheme, testnet Taproot address, and output public key presence.
     *
     * @param info deposit keyset returned by the Vault mesh
     * @return whether the keyset satisfies the configured readiness contract
     */
    private static boolean validKeyset(VaultMeshDepositInfo info) {
        if (info == null
                || !EXPECTED_SCHEME.equals(info.scheme())
                || info.address() == null
                || !info.address().startsWith("tb1p")
                || info.outputPubkeyHex() == null
                || info.outputPubkeyHex().isBlank()) {
            return false;
        }
        return "testnet3".equalsIgnoreCase(info.network())
                || "testnet".equalsIgnoreCase(info.network());
    }

    /**
     * Immutable outcome of one cached financial Vault mesh readiness evaluation.
     *
     * @param ready whether all mesh day and deposit-keyset checks passed
     * @param status bounded machine-readable health status without remote error content
     * @param checkedAt time assigned to the underlying remote check cycle
     */
    public record Snapshot(
            boolean ready,
            String status,
            Instant checkedAt) {}
}
