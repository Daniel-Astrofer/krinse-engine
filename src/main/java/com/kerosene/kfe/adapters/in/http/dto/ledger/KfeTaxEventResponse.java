package com.kerosene.kfe.adapters.in.http.dto.ledger;

import java.time.LocalDateTime;
import java.util.UUID;

/** Tax-reporting projection for one financial event associated with a user account.
 * @param id tax event identifier
 * @param eventType source event category from which the report row was derived
 * @param asset asset ticker for the reported quantity
 * @param quantitySats event quantity in satoshis
 * @param classification user or operator classification used in tax exports
 * @param sourceRef external or internal source reference for traceability
 * @param createdAt time when the underlying event was recorded
 * @param accountId associated account identifier, when available
 * @param cardId associated card identifier, when applicable
 * @param walletId associated wallet identifier, when applicable
 * @param purgeAfter retention deadline after which the row may be removed
 */
public record KfeTaxEventResponse(
        String id,
        String eventType,
        String asset,
        long quantitySats,
        String classification,
        String sourceRef,
        LocalDateTime createdAt,
        UUID accountId,
        UUID cardId,
        UUID walletId,
        LocalDateTime purgeAfter) {
}
