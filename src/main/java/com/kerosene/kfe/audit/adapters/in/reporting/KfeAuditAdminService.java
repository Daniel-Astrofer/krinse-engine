package com.kerosene.kfe.audit.adapters.in.reporting;

import com.kerosene.kfe.audit.adapters.out.crypto.KfeHashService;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.adapters.in.http.dto.audit.KfeAuditEventResponse;
import com.kerosene.kfe.adapters.in.http.dto.audit.KfeAuditLatestResponse;
import com.kerosene.kfe.adapters.in.http.dto.audit.KfeAuditRootResponse;
import com.kerosene.kfe.adapters.out.persistence.model.audit.KfeAuditLogEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.audit.KfeAuditHashRow;
import com.kerosene.kfe.adapters.out.persistence.repository.audit.KfeAuditLogRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Read-only administrative queries for the append-only financial audit chain.
 * Exposes recent events, per-transaction history, and a Merkle-style root over persisted event hashes.
 */
@Service
public class KfeAuditAdminService {

    /** Deterministic root returned when the audit table contains no events. */
    private static final String EMPTY_ROOT = "0".repeat(64);
    /** Maximum number of audit hash rows loaded per database page while computing the root. */
    private static final int ROOT_PAGE_SIZE = 1_000;

    /** Repository for ordered audit events and their hash-only projection. */
    private final KfeAuditLogRepository repository;
    /** Hash implementation used to combine adjacent event hashes into parent levels. */
    private final KfeHashService hashService;

    /**
     * Creates the audit query service with persistence and hash dependencies.
     *
     * @param repository source of ordered events and sequence-paged hash rows
     * @param hashService SHA-256 implementation for Merkle-style parent nodes
     */
    public KfeAuditAdminService(KfeAuditLogRepository repository, KfeHashService hashService) {
        this.repository = repository;
        this.hashService = hashService;
    }

    /**
     * Returns the newest audit event together with a root computed from the current full chain.
     *
     * @return latest event (null when empty) and root summary
     */
    @Transactional(readOnly = true)
    public KfeAuditLatestResponse latest() {
        KfeAuditEventResponse latest = repository.findTopByOrderBySequenceNumberDesc()
                .map(this::toResponse)
                .orElse(null);
        return new KfeAuditLatestResponse(latest, root());
    }

    /**
     * Returns the most recent audit events in descending sequence order with a bounded page size.
     * Requested limits are clamped to the inclusive range 1..500.
     *
     * @param limit desired number of entries
     * @return immutable newest-first event responses
     */
    @Transactional(readOnly = true)
    public List<KfeAuditEventResponse> events(int limit) {
        int safeLimit = Math.max(1, Math.min(500, limit));
        return repository.findAllByOrderBySequenceNumberDesc(PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Returns all audit events associated with one transaction in ascending sequence order.
     *
     * @param transactionId transaction identifier to filter by
     * @return transaction events ordered from earliest to latest
     */
    @Transactional(readOnly = true)
    public List<KfeAuditEventResponse> transactionEvents(UUID transactionId) {
        return repository.findByTransactionIdOrderBySequenceNumberAsc(transactionId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Recomputes the audit root over all stored event hashes using bounded database pages.
     * Odd levels duplicate their last hash when pairing, and an empty chain uses {@link #EMPTY_ROOT}.
     *
     * @return root hash, event count, first/last sequence, and computation timestamp
     */
    @Transactional(readOnly = true)
    public KfeAuditRootResponse root() {
        List<String> level = new ArrayList<>();
        Long fromSequence = null;
        Long toSequence = 0L;
        long eventCount = 0L;

        while (true) {
            List<KfeAuditHashRow> rows = repository.findHashRowsAfterSequence(
                    toSequence,
                    PageRequest.of(0, ROOT_PAGE_SIZE));
            if (rows.isEmpty()) {
                break;
            }
            for (KfeAuditHashRow row : rows) {
                if (fromSequence == null) {
                    fromSequence = row.getSequenceNumber();
                }
                toSequence = row.getSequenceNumber();
                eventCount++;
                level.add(row.getEventHash());
            }
        }

        if (level.isEmpty()) {
            return new KfeAuditRootResponse(EMPTY_ROOT, 0L, null, null, LocalDateTime.now(java.time.ZoneOffset.UTC));
        }

        while (level.size() > 1) {
            level = nextLevel(level);
        }
        return new KfeAuditRootResponse(
                level.getFirst(),
                eventCount,
                fromSequence,
                toSequence,
                LocalDateTime.now(java.time.ZoneOffset.UTC));
    }

    /**
     * Hashes adjacent nodes to form the next Merkle-style level, duplicating an unpaired final node.
     *
     * @param level ordered hashes at the current tree level
     * @return parent hashes at the next level
     */
    private List<String> nextLevel(List<String> level) {
        ArrayList<String> next = new ArrayList<>();
        for (int index = 0; index < level.size(); index += 2) {
            String left = level.get(index);
            String right = index + 1 < level.size() ? level.get(index + 1) : left;
            next.add(hashService.sha256(left + "|" + right));
        }
        return next;
    }

    /**
     * Projects a persistence entity into the API response without exposing mutable entity state.
     *
     * @param event stored audit event
     * @return response carrying event identifiers, state transition, hashes, and timestamp
     */
    private KfeAuditEventResponse toResponse(KfeAuditLogEntity event) {
        return new KfeAuditEventResponse(
                event.getSequenceNumber(),
                event.getId(),
                event.getTransactionId(),
                event.getWalletId(),
                event.getEventType(),
                event.getFromStatus(),
                event.getToStatus(),
                event.getPayloadHash(),
                event.getPreviousHash(),
                event.getEventHash(),
                event.getCreatedAt());
    }
}
