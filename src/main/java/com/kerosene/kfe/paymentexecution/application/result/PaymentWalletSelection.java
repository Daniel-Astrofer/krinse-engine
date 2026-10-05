package com.kerosene.kfe.paymentexecution.application.result;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentWalletSnapshot;

/**
 * Validated source and destination wallet snapshots selected for a payment flow.
 * @param source owned source snapshot, or null when the route does not debit a wallet
 * @param destination validated destination snapshot, or null for outbound-only routes
 */
public record PaymentWalletSelection(PaymentWalletSnapshot source, PaymentWalletSnapshot destination) {
    /** Reports whether the selected flow requires source-wallet funds reservation. */
    /** @return true when a source wallet was selected */
    public boolean requiresSourceReserve() { return source != null; }
}
