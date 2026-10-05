package com.kerosene.kfe.adapters.in.http.dto.ledger;

import java.util.List;

/** Tax-event export payload with its format metadata and included event records.
 *
 * @param format export encoding or file format selected for the response
 * @param filename suggested download filename
 * @param educationalNotice explanatory notice accompanying tax information
 * @param content serialized export body
 * @param events tax events represented in the export
 */
public record KfeTaxEventsExportResponse(
        String format,
        String filename,
        String educationalNotice,
        String content,
        List<KfeTaxEventResponse> events) {
}
