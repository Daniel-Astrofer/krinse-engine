package com.kerosene.kfe.paymentexecution.application.result;

import java.math.BigDecimal;

/**
 * Raw rates retained for historical display snapshots, including unavailable or invalid values.
 * These informational values do not participate in pricing or settlement.
 * @param btcUsd current BTC/USD rate, if available
 * @param btcEur current BTC/EUR rate, if available
 * @param btcBrl current BTC/BRL rate, if available
 */
public record PaymentDisplayRates(BigDecimal btcUsd, BigDecimal btcEur, BigDecimal btcBrl) {
}
