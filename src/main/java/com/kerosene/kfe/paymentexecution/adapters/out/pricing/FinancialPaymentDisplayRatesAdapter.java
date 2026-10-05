package com.kerosene.kfe.paymentexecution.adapters.out.pricing;

import com.kerosene.common.financial.operations.FinancialTickerPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentDisplayRatesPort;
import com.kerosene.kfe.paymentexecution.application.result.PaymentDisplayRates;
import org.springframework.stereotype.Component;

/** Captures the three historical display rates in their original lookup order; provider failures propagate. */
@Component
public class FinancialPaymentDisplayRatesAdapter implements PaymentDisplayRatesPort {

    /** Cross-service ticker provider for current fiat display rates. */
    private final FinancialTickerPort ticker;

    /** Creates the adapter with the financial ticker provider. */
    public FinancialPaymentDisplayRatesAdapter(FinancialTickerPort ticker) {
        this.ticker = ticker;
    }

    /** Reads USD, EUR, and BRL rates in that order and propagates provider failures. */
    @Override
    public PaymentDisplayRates currentRates() {
        return new PaymentDisplayRates(ticker.getPrice("usd"), ticker.getPrice("eur"), ticker.getPrice("brl"));
    }
}
