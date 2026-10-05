package com.kerosene.kfe.wallet.adapters.in.scheduling;

import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.kerosene.common.vaultmesh.governance.VaultMeshDayAdvanceResult;
import com.kerosene.common.vaultmesh.governance.VaultMeshDayStatus;
import com.kerosene.common.vaultmesh.governance.VaultMeshReshareResult;
import com.kerosene.common.vaultmesh.settlement.VaultMeshSettlementPort;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * KFE requests daily vault-mesh day advance + reshare; vaults execute.
 *
 * <p>Idempotent: when mesh day_epoch is already UTC today, the tick is a no-op.
 * Vaults do not self-cron the calendar day — this worker is the orchestrator.
 */
@Component
@ConditionalOnProperty(name = "kfe.vaultmesh.enabled", havingValue = "true")
@ConditionalOnProperty(name = "kfe.vaultmesh.day-rotation.enabled", havingValue = "true")
public class KfeVaultMeshDayRotationWorker {

    /** Logger for day-advance and reshare operational outcomes. */
    private static final Logger log = LoggerFactory.getLogger(KfeVaultMeshDayRotationWorker.class);

    /** Vault Mesh boundary that reports status and accepts day/reshare operations. */
    private final VaultMeshSettlementPort settlementPort;
    /** Legacy configured voter identifier, retained for observability during vault-derived identity. */
    private final String voterId;
    /** Whether successful day advancement should be followed by an explicit reshare request. */
    private final boolean triggerReshare;
    /** Reason label attached to an explicit reshare request. */
    private final String reshareReason;
    /** UTC clock used to compute today's target, injectable for deterministic tests. */
    private final Clock clock;

    /** Wires production configuration and uses the system UTC clock. */
    @Autowired
    public KfeVaultMeshDayRotationWorker(
            VaultMeshSettlementPort settlementPort,
            @Value("${kfe.vaultmesh.day-rotation.voter-id:kfe}") String voterId,
            @Value("${kfe.vaultmesh.day-rotation.trigger-reshare:true}") boolean triggerReshare,
            @Value("${kfe.vaultmesh.day-rotation.reshare-reason:kfe-day-rotation}") String reshareReason) {
        this(settlementPort, voterId, triggerReshare, reshareReason, Clock.systemUTC());
    }

    /** Creates the worker with normalized settings and an injectable clock for deterministic tests. */
    KfeVaultMeshDayRotationWorker(
            VaultMeshSettlementPort settlementPort,
            String voterId,
            boolean triggerReshare,
            String reshareReason,
            Clock clock) {
        this.settlementPort = settlementPort;
        this.voterId = voterId == null || voterId.isBlank() ? "kfe" : voterId.trim();
        this.triggerReshare = triggerReshare;
        this.reshareReason =
                reshareReason == null || reshareReason.isBlank() ? "kfe-day-rotation" : reshareReason.trim();
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Cron schedule. Special value {@code -} disables (Spring). Prefer fixed-delay for lab polling.
     */
    @Scheduled(cron = "${kfe.vaultmesh.day-rotation.cron:-}")
    public void tickCron() {
        rotateIfNeeded();
    }

    /**
     * Fixed-delay poll. Set {@code fixed-delay-ms} to a positive value for lab; leave at default
     * when using cron-only (still polls every 15m unless overridden to a large value).
     */
    @Scheduled(
            fixedDelayString = "${kfe.vaultmesh.day-rotation.fixed-delay-ms:900000}",
            initialDelayString = "${kfe.vaultmesh.day-rotation.initial-delay-ms:60000}")
    public void tickFixedDelay() {
        rotateIfNeeded();
    }

    /**
     * Reads mesh day status and advances it to the needed UTC epoch when behind.
     *
     * <p>A current/stale-safe day is an idempotent no-op. The worker requests a coordinator-controlled
     * advance and optionally a reshare. A failed explicit reshare is reported as an advanced day with
     * {@code reshared=false}, because daily policy may already have reshared during advancement.
     *
     * @return operational outcome for monitoring and deterministic tests
     */
    public Outcome rotateIfNeeded() {
        String utcToday = LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString();
        VaultMeshDayStatus status = settlementPort.getDayStatus();
        if (status.error() != null && !status.stale()) {
            log.warn("[KFE VaultMesh Day] status failed: {}", status.error());
            return Outcome.failed(status.error());
        }
        if (status.upToDate() || (status.dayEpoch() != null && status.dayEpoch().compareTo(utcToday) >= 0)) {
            log.debug("[KFE VaultMesh Day] already current dayEpoch={} utcToday={}", status.dayEpoch(), utcToday);
            return Outcome.noop(status.dayEpoch() == null ? utcToday : status.dayEpoch());
        }

        String target = status.neededDayEpoch() == null || status.neededDayEpoch().isBlank()
                ? utcToday
                : status.neededDayEpoch().trim();
        log.info(
                "[KFE VaultMesh Day] requesting coordinator advance meshDay={} → target={} (legacy voter-id={})",
                status.dayEpoch(),
                target,
                voterId);

        // /v1/day/vote is reserved for authenticated Vault peers. The shared
        // /v1/day/advance operation records the coordinator's own vote and
        // gathers peer votes before checking quorum; KFE must not impersonate
        // a voter or bypass that threshold.
        VaultMeshDayAdvanceResult advanced = settlementPort.advanceDay();
        if (!advanced.ok()) {
            log.warn("[KFE VaultMesh Day] advance failed: {}", advanced.error());
            return Outcome.failed(advanced.error());
        }

        if (triggerReshare) {
            VaultMeshReshareResult reshare = settlementPort.triggerReshare(reshareReason);
            if (!reshare.ok()) {
                // Daily policy may already have reshared on advance; treat conflict as soft.
                log.warn(
                        "[KFE VaultMesh Day] reshare trigger after advance: {} (dayEpoch={})",
                        reshare.error(),
                        advanced.dayEpoch());
                return Outcome.advanced(advanced.dayEpoch(), false, reshare.error());
            }
            log.info(
                    "[KFE VaultMesh Day] advanced+reshared dayEpoch={} policy={}",
                    advanced.dayEpoch(),
                    reshare.policy());
            return Outcome.advanced(advanced.dayEpoch(), true, null);
        }

        log.info("[KFE VaultMesh Day] advanced dayEpoch={} (reshare trigger skipped)", advanced.dayEpoch());
        return Outcome.advanced(advanced.dayEpoch(), false, null);
    }

    /**
     * Summary of one scheduled rotation attempt.
     *
     * @param kind whether the attempt was a no-op, successful advance, or failure
     * @param dayEpoch resulting or observed epoch when known
     * @param reshared whether an explicit reshare request succeeded
     * @param error safe failure detail when an operation did not complete
     */
    public record Outcome(Kind kind, String dayEpoch, boolean reshared, String error) {
        /** Result categories returned to scheduling and operations callers. */
        public enum Kind {
            /** Mesh is already at or beyond the current UTC day. */
            NOOP,
            /** Day advancement succeeded, with reshare reflected separately. */
            ADVANCED,
            /** Status or coordinator-controlled advancement failed. */
            FAILED
        }

        /** Creates a no-op outcome for a mesh already current through the given epoch. */
        static Outcome noop(String dayEpoch) {
            return new Outcome(Kind.NOOP, dayEpoch, false, null);
        }

        /** Creates a successful day-advance outcome and records optional reshare failure detail. */
        static Outcome advanced(String dayEpoch, boolean reshared, String error) {
            return new Outcome(Kind.ADVANCED, dayEpoch, reshared, error);
        }

        /** Creates a failed outcome when the mesh could not safely advance. */
        static Outcome failed(String error) {
            return new Outcome(Kind.FAILED, null, false, error);
        }
    }
}
