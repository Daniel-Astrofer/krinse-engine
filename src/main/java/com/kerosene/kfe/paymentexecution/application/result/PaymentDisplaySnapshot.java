package com.kerosene.kfe.paymentexecution.application.result;

import java.math.BigDecimal;

/**
 * Presentation-only values: never used for reservations, ledger movements, or settlement decisions.
 * @param btcUsd current BTC/USD display rate
 * @param btcEur current BTC/EUR display rate
 * @param btcBrl current BTC/BRL display rate
 * @param amountUsd receiver amount converted to USD for display
 * @param amountEur receiver amount converted to EUR for display
 * @param amountBrl receiver amount converted to BRL for display
 */
public record PaymentDisplaySnapshot(
        BigDecimal btcUsd,
        BigDecimal btcEur,
        BigDecimal btcBrl,
        BigDecimal amountUsd,
        BigDecimal amountEur,
        BigDecimal amountBrl) {
}
