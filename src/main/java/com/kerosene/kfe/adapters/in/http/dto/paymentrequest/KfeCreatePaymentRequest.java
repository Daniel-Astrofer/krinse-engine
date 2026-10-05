package com.kerosene.kfe.adapters.in.http.dto.paymentrequest;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.kerosene.kfe.adapters.out.persistence.model.shared.KfeRail;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Validated API payload for creating a payment request and selecting its receiving rails.
 * A caller may use the legacy single {@code rail} field or the preferred {@code rails} collection;
 * service normalization chooses the default on-chain rail if neither supplies a value.
 *
 * @param walletId wallet that owns and receives the payment request
 * @param rail legacy single-rail selector retained for older clients
 * @param rails preferred set of rails to activate for the request
 * @param amountSats optional fixed requested amount in satoshis; null represents an open amount
 * @param description short request description shown to the payer
 * @param memo longer merchant memo associated with the request
 * @param payerHint optional hint identifying the intended payer
 * @param expiresAt local date and time after which the request is no longer payable
 * @param issueFreshAddress whether creation should issue a new receiving address
 * @param webhookUrl optional event callback URL, limited to 2,048 characters
 */
public record KfeCreatePaymentRequest(
        /** Required wallet identifier used to scope address issuance and request ownership. */
        @NotNull UUID walletId,
        /** Legacy: single rail (backward compat). Prefer rails (list) for multi-rail. */
        /** Legacy selector for one rail; retained for backward compatibility with existing clients. */
        KfeRail rail,
        /** Rails to activate for this payment request. Defaults to [ONCHAIN] when both are null/empty. */
        /** Preferred rail set; an empty or absent value is normalized by the service to on-chain. */
        List<KfeRail> rails,
        /** Positive fixed amount in satoshis when the request is not open-amount. */
        @Min(1) Long amountSats,
        /** Public-facing payment description, capped at 180 characters. */
        @Size(max = 180) String description,
        /** Optional merchant memo retained with the payment request, capped at 255 characters. */
        @Size(max = 255) String memo,
        /** Optional payer-identification hint, capped at 120 characters. */
        @Size(max = 120) String payerHint,
        /** Optional expiration wall-clock time interpreted by the payment-request service. */
        LocalDateTime expiresAt,
        /** Whether a new receive address should be issued instead of reusing the wallet address. */
        Boolean issueFreshAddress,
        /** Optional webhook URL for payment event delivery. Max 2048 chars. */
        @Size(max = 2048) String webhookUrl) {
}
