package com.kerosene.kfe.adapters.in.http.dto.ledger;

import java.time.Instant;
import java.util.UUID;

/**
 * Recent-activity row for dashboard / local merge.
 *
 * <p><b>Client merge contract</b>
 * <ul>
 *   <li>Stable identity: {@code id} == {@code transactionId} (never recreate on status change).
 *   <li>Sort key: {@code createdAt} DESC, then {@code transactionId} DESC — never reorder by
 *       {@code updatedAt}.
 *   <li>On push/pull of the same {@code transactionId}: update status/payload/updatedAt in place;
 *       keep {@code createdAt} from the first time the row was known.
 *   <li>{@code displayStatus}: PENDING | CONFIRMED | FAILED for badges.
 * </ul>
 * @param id stable activity-row identity, equal to {@code transactionId}
 * @param transactionId source transaction identity used for merge and ordering
 * @param walletId wallet associated with the transaction
 * @param status detailed raw ledger status
 * @param displayStatus coarse status label used by activity badges
 * @param displayPayloadJson serialized safe summary shown in the activity feed
 * @param createdAt original transaction creation instant used as stable sort key
 * @param updatedAt latest transaction status update instant
 * @param expiresAt expiration time for pending activity, when applicable
 */
public record KfeStatementItem(
        UUID id,
        UUID transactionId,
        UUID walletId,
        String status,
        String displayStatus,
        String displayPayloadJson,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt) {
}
