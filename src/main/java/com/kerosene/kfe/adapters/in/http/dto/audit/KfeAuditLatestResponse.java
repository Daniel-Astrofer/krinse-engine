package com.kerosene.kfe.adapters.in.http.dto.audit;

/** Combined latest audit event and integrity root returned by the admin API.
 * @param latestEvent newest persisted audit entry, or {@code null} when the chain is empty
 * @param root integrity root summary calculated for the audit chain
 */
public record KfeAuditLatestResponse(
        KfeAuditEventResponse latestEvent,
        KfeAuditRootResponse root) {
}
