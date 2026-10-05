package com.kerosene.kfe.adapters.in.http.dto.ledger;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request to assign a tax classification to a ledger event.
 *
 * @param classification classification label to store; must be non-blank and at most 64 characters
 */
public record KfeClassifyTaxEventRequest(
        @NotBlank @Size(max = 64) String classification) {
}
