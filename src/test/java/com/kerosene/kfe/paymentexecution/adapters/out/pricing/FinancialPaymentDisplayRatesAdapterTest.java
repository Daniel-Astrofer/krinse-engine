package com.kerosene.kfe.paymentexecution.adapters.out.pricing;

import com.kerosene.common.financial.operations.FinancialTickerPort;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinancialPaymentDisplayRatesAdapterTest {

    private final FinancialTickerPort ticker = mock(FinancialTickerPort.class);
    private final FinancialPaymentDisplayRatesAdapter adapter = new FinancialPaymentDisplayRatesAdapter(ticker);

    @Test
    void readsTheOriginalThreeCurrenciesInOrderWithoutAlteringPrecision() {
        var usd = new BigDecimal("59000.123456789");
        var eur = new BigDecimal("53000.99");
        var brl = new BigDecimal("300000.000000001");
        when(ticker.getPrice("usd")).thenReturn(usd);
        when(ticker.getPrice("eur")).thenReturn(eur);
        when(ticker.getPrice("brl")).thenReturn(brl);

        var result = adapter.currentRates();

        assertThat(result.btcUsd()).isSameAs(usd);
        assertThat(result.btcEur()).isSameAs(eur);
        assertThat(result.btcBrl()).isSameAs(brl);
        var order = inOrder(ticker);
        order.verify(ticker).getPrice("usd");
        order.verify(ticker).getPrice("eur");
        order.verify(ticker).getPrice("brl");
        order.verifyNoMoreInteractions();
    }

    @Test
    void preservesNullZeroAndNegativeRawPrices() {
        var zero = new BigDecimal("0.0000");
        var negative = new BigDecimal("-100.00");
        when(ticker.getPrice("usd")).thenReturn(null);
        when(ticker.getPrice("eur")).thenReturn(zero);
        when(ticker.getPrice("brl")).thenReturn(negative);

        var result = adapter.currentRates();

        assertThat(result.btcUsd()).isNull();
        assertThat(result.btcEur()).isSameAs(zero);
        assertThat(result.btcBrl()).isSameAs(negative);
    }

    @Test
    void providerFailureStopsReadsAndPropagates() {
        var failure = new IllegalStateException("ticker unavailable");
        when(ticker.getPrice("eur")).thenThrow(failure);

        assertThatThrownBy(() -> adapter.currentRates()).isSameAs(failure);

        verify(ticker).getPrice("usd");
        verify(ticker).getPrice("eur");
        verify(ticker, never()).getPrice("brl");
    }
}
