package com.kerosene.kfe.liquidity.adapters.in.scheduling;

import com.kerosene.kfe.liquidity.adapters.in.KfeChannelLifecycleService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelOperationDecisionEntity;
import com.kerosene.kfe.adapters.out.rail.channels.ChannelsMeshInjectGateway;
import com.kerosene.kfe.adapters.out.persistence.repository.liquidity.KfeChannelOperationDecisionRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Reconciles CHANNELS mesh inject crash windows:
 * <ul>
 *   <li>{@code OPENED_COMMIT_PENDING} → idempotent Intent commit retry</li>
 *   <li>orphaned {@code RESERVED} (no open) past TTL → release Intent</li>
 * </ul>
 */
@Service
public class KfeChannelMeshInjectReconciler {

    private static final Logger log = LoggerFactory.getLogger(KfeChannelMeshInjectReconciler.class);

    private final KfeChannelOperationDecisionRepository decisionRepository;
    private final ChannelsMeshInjectGateway channelsMeshInject;
    private final KfeChannelLifecycleService lifecycleService;
    private final boolean enabled;
    private final int batchSize;
    private final long orphanReserveTtlMinutes;

    public KfeChannelMeshInjectReconciler(
            KfeChannelOperationDecisionRepository decisionRepository,
            ChannelsMeshInjectGateway channelsMeshInject,
            KfeChannelLifecycleService lifecycleService,
            @Value("${kfe.channel.mesh-inject-reconciler.enabled:true}") boolean enabled,
            @Value("${kfe.channel.mesh-inject-reconciler.batch-size:20}") int batchSize,
            @Value("${kfe.channel.mesh-inject-reconciler.orphan-reserve-ttl-minutes:10}")
                    long orphanReserveTtlMinutes) {
        this.decisionRepository = decisionRepository;
        this.channelsMeshInject = channelsMeshInject;
        this.lifecycleService = lifecycleService;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.orphanReserveTtlMinutes = Math.max(1L, orphanReserveTtlMinutes);
    }

    @Scheduled(
            fixedDelayString = "${kfe.channel.mesh-inject-reconciler.fixed-delay-ms:60000}",
            initialDelayString = "${kfe.channel.mesh-inject-reconciler.initial-delay-ms:45000}")
    public void reconcile() {
        if (!enabled) {
            return;
        }
        retryPendingCommits(batchSize);
        releaseOrphanedReserves(batchSize);
    }

    @Transactional
    public int retryPendingCommits(int limit) {
        List<KfeChannelOperationDecisionEntity> pending =
                decisionRepository.findByMeshInjectPhaseAndExecutedFalseOrderByCreatedAtAsc(
                        KfeChannelLifecycleService.PHASE_OPENED_COMMIT_PENDING,
                        Pageable.ofSize(Math.max(1, limit)));
        int done = 0;
        for (KfeChannelOperationDecisionEntity row : pending) {
            try {
                lifecycleService.retryCommit(row.getId());
                done++;
            } catch (RuntimeException ex) {
                log.warn(
                        "[KFE Channel Inject] commit retry failed decision={}: {}",
                        row.getId(),
                        ex.getMessage());
            }
        }
        return done;
    }

    @Transactional
    public int releaseOrphanedReserves(int limit) {
        LocalDateTime cutoff =
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(orphanReserveTtlMinutes);
        List<KfeChannelOperationDecisionEntity> orphans =
                decisionRepository.findOrphanedReserves(cutoff, Pageable.ofSize(Math.max(1, limit)));
        int released = 0;
        for (KfeChannelOperationDecisionEntity row : orphans) {
            String intentId = row.getMeshIntentId();
            if (intentId == null || intentId.isBlank()) {
                row.setMeshInjectPhase(KfeChannelLifecycleService.PHASE_RELEASED);
                row.setDecisionReason("MESH_ORPHAN_RESERVE_CLEARED_NO_INTENT");
                decisionRepository.save(row);
                released++;
                continue;
            }
            long amount = row.getAmountSats() != null ? row.getAmountSats() : 0L;
            ChannelsMeshInjectGateway.InjectResult result =
                    channelsMeshInject.releaseOpen(intentId, amount, row.getPeerPubkey());
            if (result.authorized()) {
                row.setMeshInjectPhase(KfeChannelLifecycleService.PHASE_RELEASED);
                row.setDecisionReason("MESH_ORPHAN_RESERVE_RELEASED");
                decisionRepository.save(row);
                released++;
            } else {
                log.warn(
                        "[KFE Channel Inject] orphan release failed decision={} reason={}",
                        row.getId(),
                        result.reasonCode());
            }
        }
        return released;
    }
}
