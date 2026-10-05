package com.kerosene.kfe.liquidity.adapters.out.persistence;

import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.liquidity.adapters.out.observability.KfeCapacitySignalStore;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeLightningLiquidityReservationEntity;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeLiquidityReservationStatus;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningClient;
import com.kerosene.kfe.adapters.out.rail.lightning.LightningPaymentGateway;
import com.kerosene.kfe.adapters.out.persistence.repository.liquidity.KfeLightningLiquidityReservationRepository;
import com.kerosene.kfe.liquidity.domain.model.LiquidityReservationSnapshot;
import com.kerosene.kfe.liquidity.domain.model.LiquidityReservationState;
import com.kerosene.kfe.liquidity.domain.policy.LiquidityReservationDecision;
import com.kerosene.kfe.liquidity.domain.policy.LiquidityReservationPolicy;
import com.kerosene.kfe.liquidity.application.port.in.LiquidityReservationUseCase;

import java.util.UUID;
import java.util.Map;

/**
 * Outbound Lightning liquidity: capacity probe + reservation until HTLC terminal state.
 * Pooled node model — free capacity = local balance − sum(HELD reservations).
 */
@Service
public class KfeLightningLiquidityService implements LiquidityReservationUseCase {

    /** Stable advisory lock key for the platform LN outbound pool. */
    static final long POOL_LOCK_KEY = 0x4B46454C4E4C5154L; // "KFELNLIQ" truncated

    private final ObjectProvider<LightningClient> lightningClientProvider;
    private final ObjectProvider<LightningPaymentGateway> paymentGatewayProvider;
    private final KfeLightningLiquidityReservationRepository reservationRepository;
    private final ObjectProvider<KfeCapacitySignalStore> capacitySignalStore;
    private final long minOutboundSats;
    private final long circuitBreakerFloorSats;
    private final int circuitBreakerStressThreshold;
    private final LiquidityReservationPolicy reservationPolicy = new LiquidityReservationPolicy();
    private volatile boolean circuitBreakerLatched;

    @Autowired(required = false)
    private KfeAuditLogService auditLogService;

    public KfeLightningLiquidityService(
            ObjectProvider<LightningClient> lightningClientProvider,
            @org.springframework.beans.factory.annotation.Qualifier("kfeExternalLightningPaymentGateway")
            ObjectProvider<LightningPaymentGateway> paymentGatewayProvider,
            KfeLightningLiquidityReservationRepository reservationRepository,
            ObjectProvider<KfeCapacitySignalStore> capacitySignalStore,
            @Value("${kfe.lightning.min-outbound-sats:0}") long minOutboundSats,
            @Value("${kfe.lightning.circuit-breaker-floor-sats:0}") long circuitBreakerFloorSats,
            @Value("${kfe.lightning.circuit-breaker-stress-threshold:10}") int circuitBreakerStressThreshold) {
        this.lightningClientProvider = lightningClientProvider;
        this.paymentGatewayProvider = paymentGatewayProvider;
        this.reservationRepository = reservationRepository;
        this.capacitySignalStore = capacitySignalStore;
        this.minOutboundSats = Math.max(0L, minOutboundSats);
        this.circuitBreakerFloorSats = Math.max(0L, circuitBreakerFloorSats);
        this.circuitBreakerStressThreshold = Math.max(1, circuitBreakerStressThreshold);
    }

    public boolean isLive() {
        LightningPaymentGateway gateway = paymentGatewayProvider.getIfAvailable();
        if (gateway != null && gateway.isLive()) {
            return true;
        }
        LightningClient client = lightningClientProvider.getIfAvailable();
        return client != null;
    }

    /** Local channel balance in sats, or -1 if not probeable. */
    public long outboundCapacitySats() {
        LightningClient client = lightningClientProvider.getIfAvailable();
        if (client == null) {
            return -1L;
        }
        try {
            return Math.max(0L, client.getLocalBalance());
        } catch (RuntimeException ex) {
            return -1L;
        }
    }

    public long heldReservationSats() {
        return Math.max(0L, reservationRepository.sumAmountByStatus(KfeLiquidityReservationStatus.HELD));
    }

    /** Free outbound capacity after existing HELD reservations. */
    public long freeOutboundCapacitySats() {
        long capacity = outboundCapacitySats();
        if (capacity < 0L) {
            return -1L;
        }
        return Math.max(0L, capacity - heldReservationSats());
    }

    public boolean canCoverOutbound(long totalDebitSats) {
        long free = freeOutboundCapacitySats();
        if (free < 0L) {
            return false;
        }
        if (totalDebitSats <= 0L) {
            return false;
        }
        if (minOutboundSats > 0L && free < minOutboundSats) {
            return false;
        }
        return free >= totalDebitSats;
    }

    /**
     * Circuit breaker with hysteresis and sustained-stress awareness.
     *
     * <p>Trips when free (post-reservation) outbound capacity drops below the floor OR
     * sustained liquidity-reject count exceeds the stress threshold. Once tripped, stays
     * open until free capacity recovers above the recovery threshold (1.2× floor).
     */
    public boolean circuitBreakerOpen() {
        if (circuitBreakerFloorSats <= 0L) {
            return false;
        }

        long free = freeOutboundCapacitySats();

        // Probe failure → fail closed (trip)
        if (free < 0L) {
            circuitBreakerLatched = true;
            return true;
        }

        long recoveryThreshold = (long) (circuitBreakerFloorSats * 1.2);

        // Sustained stress: trip on repeated liquidity rejects
        KfeCapacitySignalStore signals = capacitySignalStore.getIfAvailable();
        if (signals != null) {
            long recentRejects = signals.liquidityRejectsInWindow();
            if (recentRejects >= circuitBreakerStressThreshold) {
                circuitBreakerLatched = true;
                return true;
            }
        }

        // Already latched → stay open until recovery threshold met
        if (circuitBreakerLatched) {
            if (free >= recoveryThreshold) {
                circuitBreakerLatched = false;
                return false;
            }
            return true;
        }

        // Below floor → trip and latch
        if (free < circuitBreakerFloorSats) {
            circuitBreakerLatched = true;
            return true;
        }

        return false;
    }

    /**
     * Atomically reserve outbound capacity until HTLC resolves.
     * Idempotent per transactionId.
     */
    @Transactional
    public void reserveForTransaction(UUID transactionId, long amountSats) {
        if (transactionId == null) {
            throw new IllegalArgumentException("transactionId is required for liquidity reservation.");
        }
        if (amountSats <= 0L) {
            throw new IllegalArgumentException("liquidity reservation amount must be positive.");
        }
        var existing = reservationRepository.findByTransactionId(transactionId);
        if (existing.isPresent()) {
            KfeLightningLiquidityReservationEntity row = existing.orElseThrow();
            var decision = reservationPolicy.reserve(
                    new LiquidityReservationSnapshot(
                            transactionId,
                            row.getAmountSats(),
                            switch (row.getStatus()) {
                                case HELD -> LiquidityReservationState.HELD;
                                case RELEASED -> LiquidityReservationState.RELEASED;
                                case CONSUMED -> LiquidityReservationState.CONSUMED;
                            }),
                    0L,
                    amountSats);
            if (decision == LiquidityReservationDecision.ALREADY_HELD) return;
            throw new IllegalStateException("Lightning liquidity reservation is already terminal.");
        }
        reservationRepository.acquirePoolLock(POOL_LOCK_KEY);
        if (!canCoverOutbound(amountSats)) {
            throw new IllegalStateException(
                    "Insufficient Lightning outbound liquidity for reservation of " + amountSats + " sats.");
        }
        KfeLightningLiquidityReservationEntity reservation = new KfeLightningLiquidityReservationEntity();
        reservation.setTransactionId(transactionId);
        reservation.setAmountSats(amountSats);
            reservation.setStatus(KfeLiquidityReservationStatus.HELD);
            try {
                reservationRepository.saveAndFlush(reservation);
                audit(transactionId, amountSats, "HELD");
            } catch (DataIntegrityViolationException ex) {
            // Concurrent insert for same tx — treat as idempotent success.
            if (reservationRepository.findByTransactionId(transactionId).isEmpty()) {
                throw ex;
            }
        }
    }

    @Transactional
    public void consumeForTransaction(UUID transactionId) {
        finalize(transactionId, true);
    }

    @Transactional
    public void releaseForTransaction(UUID transactionId) {
        finalize(transactionId, false);
    }

    private void finalize(UUID transactionId, boolean consumed) {
        if (transactionId == null) {
            return;
        }
        reservationRepository.findByTransactionId(transactionId).ifPresent(reservation -> {
            if (reservation.getStatus() != KfeLiquidityReservationStatus.HELD) {
                return;
            }
            if (consumed) {
                reservation.markConsumed();
            } else {
                reservation.markReleased();
            }
            reservationRepository.save(reservation);
            audit(transactionId, reservation.getAmountSats(), consumed ? "CONSUMED" : "RELEASED");
        });
    }

    private void audit(UUID transactionId, long amountSats, String status) {
        if (auditLogService == null) {
            return;
        }
        auditLogService.record(
                "KFE_CHANNEL_DECISION",
                transactionId,
                null,
                null,
                null,
                Map.of("transactionId", transactionId.toString(), "amountSats", amountSats, "status", status));
    }
}
