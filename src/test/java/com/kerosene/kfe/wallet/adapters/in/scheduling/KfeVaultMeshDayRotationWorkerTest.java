package com.kerosene.kfe.wallet.adapters.in.scheduling;

import com.kerosene.common.vaultmesh.governance.VaultMeshDayAdvanceResult;
import com.kerosene.common.vaultmesh.governance.VaultMeshDayStatus;
import com.kerosene.common.vaultmesh.intent.VaultMeshIntent;
import com.kerosene.common.vaultmesh.intent.VaultMeshReceipt;
import com.kerosene.common.vaultmesh.governance.VaultMeshReshareResult;
import com.kerosene.common.vaultmesh.settlement.VaultMeshSettlementPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class KfeVaultMeshDayRotationWorkerTest {

    private static final Instant FIXED = Instant.parse("2026-07-22T15:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);
    private static final String TODAY = LocalDate.ofInstant(FIXED, ZoneOffset.UTC).toString();

    @Test
    void noopWhenMeshDayAlreadyUtcToday() {
        RecordingPort port = new RecordingPort(VaultMeshDayStatus.upToDate(TODAY));
        KfeVaultMeshDayRotationWorker worker = worker(port);

        KfeVaultMeshDayRotationWorker.Outcome out = worker.rotateIfNeeded();

        assertThat(out.kind()).isEqualTo(KfeVaultMeshDayRotationWorker.Outcome.Kind.NOOP);
        assertThat(port.votes).isEmpty();
        assertThat(port.advances.get()).isZero();
        assertThat(port.reshares).isEmpty();
    }

    @Test
    void delegatesPeerVotesToVaultCoordinatorAndTriggersReshareWhenStale() {
        RecordingPort port = new RecordingPort(VaultMeshDayStatus.stale("2026-07-21", TODAY));
        KfeVaultMeshDayRotationWorker worker = worker(port);

        KfeVaultMeshDayRotationWorker.Outcome out = worker.rotateIfNeeded();

        assertThat(out.kind()).isEqualTo(KfeVaultMeshDayRotationWorker.Outcome.Kind.ADVANCED);
        assertThat(out.reshared()).isTrue();
        assertThat(out.dayEpoch()).isEqualTo(TODAY);
        assertThat(port.votes).isEmpty();
        assertThat(port.advances.get()).isEqualTo(1);
        assertThat(port.reshares).containsExactly("kfe-day-rotation");
    }

    @Test
    void idempotentSecondTickIsNoop() {
        RecordingPort port = new RecordingPort(VaultMeshDayStatus.stale("2026-07-21", TODAY));
        KfeVaultMeshDayRotationWorker worker = worker(port);

        assertThat(worker.rotateIfNeeded().kind())
                .isEqualTo(KfeVaultMeshDayRotationWorker.Outcome.Kind.ADVANCED);

        port.status = VaultMeshDayStatus.upToDate(TODAY);
        KfeVaultMeshDayRotationWorker.Outcome second = worker.rotateIfNeeded();

        assertThat(second.kind()).isEqualTo(KfeVaultMeshDayRotationWorker.Outcome.Kind.NOOP);
        assertThat(port.advances.get()).isEqualTo(1);
        assertThat(port.reshares).hasSize(1);
    }

    @Test
    void skipsReshareWhenConfiguredOff() {
        RecordingPort port = new RecordingPort(VaultMeshDayStatus.stale("2026-07-21", TODAY));
        KfeVaultMeshDayRotationWorker worker =
                new KfeVaultMeshDayRotationWorker(port, "kfe", false, "kfe-day-rotation", CLOCK);

        KfeVaultMeshDayRotationWorker.Outcome out = worker.rotateIfNeeded();

        assertThat(out.kind()).isEqualTo(KfeVaultMeshDayRotationWorker.Outcome.Kind.ADVANCED);
        assertThat(out.reshared()).isFalse();
        assertThat(port.reshares).isEmpty();
    }

    @Test
    void failsWhenVaultCoordinatorCannotEstablishQuorum() {
        RecordingPort port = new RecordingPort(VaultMeshDayStatus.stale("2026-07-21", TODAY));
        port.advanceError = "quorum not met: have 0, need 2";
        KfeVaultMeshDayRotationWorker worker = worker(port);

        KfeVaultMeshDayRotationWorker.Outcome out = worker.rotateIfNeeded();

        assertThat(out.kind()).isEqualTo(KfeVaultMeshDayRotationWorker.Outcome.Kind.FAILED);
        assertThat(out.error()).contains("quorum");
        assertThat(port.votes).isEmpty();
        assertThat(port.advances.get()).isEqualTo(1);
        assertThat(port.reshares).isEmpty();
    }

    private static KfeVaultMeshDayRotationWorker worker(VaultMeshSettlementPort port) {
        return new KfeVaultMeshDayRotationWorker(port, "kfe", true, "kfe-day-rotation", CLOCK);
    }

    private record Vote(String voter, String dayEpoch) {}

    private static final class RecordingPort implements VaultMeshSettlementPort {
        private VaultMeshDayStatus status;
        private String advanceError;
        private final List<Vote> votes = new ArrayList<>();
        private final AtomicInteger advances = new AtomicInteger();
        private final List<String> reshares = new ArrayList<>();
        private final AtomicReference<String> currentDay = new AtomicReference<>();

        private RecordingPort(VaultMeshDayStatus status) {
            this.status = status;
            this.currentDay.set(status.dayEpoch());
        }

        @Override
        public VaultMeshReceipt submitIntent(VaultMeshIntent intent) {
            return new VaultMeshReceipt(
                    intent.intentId(), VaultMeshReceipt.Status.REJECTED, "UNUSED", null, Instant.EPOCH);
        }

        @Override
        public VaultMeshDayStatus getDayStatus() {
            return status;
        }

        @Override
        public VaultMeshDayAdvanceResult voteDay(String voter, String dayEpoch) {
            votes.add(new Vote(voter, dayEpoch));
            return VaultMeshDayAdvanceResult.failed("role kfe cannot call /v1/day/vote (vault peer only)");
        }

        @Override
        public VaultMeshDayAdvanceResult advanceDay() {
            advances.incrementAndGet();
            if (advanceError != null) {
                return VaultMeshDayAdvanceResult.failed(advanceError);
            }
            currentDay.set(TODAY);
            status = VaultMeshDayStatus.upToDate(TODAY);
            return VaultMeshDayAdvanceResult.ok(TODAY, true);
        }

        @Override
        public VaultMeshReshareResult triggerReshare(String reason) {
            reshares.add(reason);
            return VaultMeshReshareResult.ok("daily", reason);
        }
    }
}
