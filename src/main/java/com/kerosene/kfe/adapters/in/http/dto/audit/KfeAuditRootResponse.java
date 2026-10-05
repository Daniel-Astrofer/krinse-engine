package com.kerosene.kfe.adapters.in.http.dto.audit;

import java.time.LocalDateTime;

/** Integrity summary over a contiguous range of persisted audit events.
 * @param merkleRoot root digest calculated from the included event hashes
 * @param eventCount number of events committed to the root
 * @param fromSequence first event sequence included, or {@code null} for an empty chain
 * @param toSequence last event sequence included, or {@code null} for an empty chain
 * @param generatedAt time when the root was computed
 */
public record KfeAuditRootResponse(
        String merkleRoot,
        long eventCount,
        Long fromSequence,
        Long toSequence,
        LocalDateTime generatedAt) {
}
